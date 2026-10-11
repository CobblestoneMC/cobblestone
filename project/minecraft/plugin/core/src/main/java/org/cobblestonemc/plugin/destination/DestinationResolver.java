/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.OptionValues;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;

/**
 * Turns the arguments a player typed into a concrete {@link MinecraftDestination}, walking the
 * {@link PlatformDestinationTree}s gathered from every registered provider.
 *
 * <h2>Addressing</h2>
 *
 * <p>A destination's <b>canonical address</b> is the full key path to it ({@code citizens npc
 * bob}). It may also be addressed by <b>any ordered subsequence of its ancestor keys plus its own
 * key</b> — {@code npc bob}, {@code citizens bob}, {@code bob} — so long as that shorter form does
 * not also address something else. If it does, the form is ambiguous and rejected ({@link
 * Ambiguous}), and the player must type a fuller path. Two things block promotion outright:
 *
 * <ul>
 *   <li>a node's own key is always required when the node is {@linkplain
 *       PlatformDestinationTree#strict() strict}, and
 *   <li>the destination's own key is always required.
 * </ul>
 *
 * <h2>Options</h2>
 *
 * <p>A destination may take {@linkplain MinecraftDestination#options() options} — tokens typed
 * after its key, such as the {@code 100 64 -200} of {@code coordinates 100 64 -200}. Options are
 * never part of the address and are never promoted: the key comes first, then one token per option,
 * in order. A destination with no options must be the last token. When a destination's key matches
 * but the tokens after it do not fit its options, and nothing else matches, the result is {@link
 * InvalidOptions} so the player can be shown how to use it.
 *
 * <p>Matching is case-insensitive throughout, so two providers registering {@code quest} and {@code
 * Quest} collide deliberately rather than silently offering the player two look-alike paths.
 *
 * <h2>Suggestions</h2>
 *
 * <p>{@link #suggest} answers "what may the next token be?", given the tokens already typed. It
 * never enumerates the whole forest: it descends only along the typed prefix, and it declines to
 * promote a level whose subtree contributes more than {@link #PROMOTION_LIMIT} tokens — such a
 * level behaves as if it were strict, for suggestion purposes only. So an {@code npc} node with
 * three hundred NPCs under it offers {@code npc} at the root rather than three hundred names, and
 * those names are never materialized. Providers that know a level is large should still mark it
 * {@code strict()}: that is cheaper (the subtree is not visited at all), and it also blocks
 * promotion during {@link #resolve}, which the limit deliberately does not.
 *
 * <p>Suggestions are also filtered for usefulness: a token is offered only if it can lead
 * somewhere. A token that would complete an address ambiguously, and that cannot be extended
 * further, is not offered — typing it could only produce an "ambiguous" error.
 *
 * <p>This is platform-neutral: callers gather the provider roots (on Paper, from the {@code
 * ServicesManager}) and pass a permission predicate; the resolver never touches a server type.
 */
public final class DestinationResolver {

  /**
   * How many tokens a promotable (non-strict) level may contribute to a suggestion set before it is
   * treated as strict and contributes only its own key.
   */
  public static final int PROMOTION_LIMIT = 16;

  private DestinationResolver() {}

  /** The outcome of resolving typed arguments against the destination forest. */
  public sealed interface Resolution<W, V> {}

  /**
   * Exactly one destination matched.
   *
   * @param destination the resolved destination
   * @param address the canonical key path that identifies it (for confirmation messages)
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record Resolved<W, V>(MinecraftDestination<W, V> destination, List<String> address)
      implements Resolution<W, V> {}

  /**
   * More than one destination matched; the player must disambiguate with a fuller path.
   *
   * @param addresses the distinct canonical key paths of the candidates, alphabetically
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record Ambiguous<W, V>(List<List<String>> addresses) implements Resolution<W, V> {}

  /**
   * No destination matched (or none the player may use).
   *
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record NotFound<W, V>() implements Resolution<W, V> {}

  /**
   * A destination's key matched, but the tokens after it do not fit its options — too few, too
   * many, or one that does not parse — and nothing else matched.
   *
   * @param address the canonical key path of the destination
   * @param options the destination's options, for a usage hint (see {@link #usage})
   * @param invalidToken the first token that did not parse, or {@code null} if the problem was the
   *     number of tokens
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record InvalidOptions<W, V>(
      List<String> address, List<DestinationOption<?>> options, String invalidToken)
      implements Resolution<W, V> {}

  /**
   * How to type a destination with options, e.g. {@code coordinates block <x> <y> <z> [world]}.
   *
   * @param address the destination's address
   * @param options its options
   * @return the usage
   */
  public static String usage(List<String> address, List<DestinationOption<?>> options) {
    StringBuilder usage = new StringBuilder(String.join(" ", address));
    for (DestinationOption<?> option : options) {
      usage.append(option.required() ? " <" : " [").append(option.name());
      usage.append(option.required() ? '>' : ']');
    }
    return usage.toString();
  }

  /**
   * Resolves the given arguments.
   *
   * @param roots the destination-tree roots from every provider
   * @param args the arguments typed so far (each a full token)
   * @param hasPermission tests whether the player holds a permission node
   * @param <W> the world type
   * @param <V> the vector type
   * @return the resolution
   */
  public static <W, V> Resolution<W, V> resolve(
      Map<String, ? extends PlatformDestinationTree<W, V>> roots,
      List<String> args,
      Predicate<String> hasPermission) {
    return resolve(roots, args, hasPermission, address -> true);
  }

  /**
   * Resolves the given arguments, also honoring the Cobblestone navigation-gate permission.
   *
   * @param roots the destination-tree roots from every provider
   * @param args the arguments typed so far (each a full token)
   * @param hasPermission tests whether the player holds a permission node (a destination's own
   *     required permissions)
   * @param canNavigate tests whether the player may navigate to a destination at a given canonical
   *     address (see {@link NavigationPermissions})
   * @param <W> the world type
   * @param <V> the vector type
   * @return the resolution
   */
  public static <W, V> Resolution<W, V> resolve(
      Map<String, ? extends PlatformDestinationTree<W, V>> roots,
      List<String> args,
      Predicate<String> hasPermission,
      Predicate<List<String>> canNavigate) {
    if (args.isEmpty()) {
      return new NotFound<>();
    }
    Results<W, V> results = new Results<>(hasPermission, canNavigate);
    for (var root : roots.entrySet()) {
      match(root.getKey(), root.getValue(), args, 0, new ArrayList<>(), results);
    }
    List<Match<W, V>> matches = results.matches;
    if (matches.isEmpty()) {
      return results.invalid.isEmpty() ? new NotFound<>() : results.invalid.getFirst();
    }
    if (matches.size() == 1) {
      return new Resolved<>(matches.getFirst().destination(), matches.getFirst().address());
    }
    // Distinct canonical addresses, first spelling wins. Sorted, so what the player is shown does
    // not depend on the order the roots happened to be iterated in.
    Map<String, List<String>> distinct = new TreeMap<>();
    for (Match<W, V> candidate : matches) {
      distinct.putIfAbsent(
          String.join(" ", candidate.address()).toLowerCase(Locale.ROOT), candidate.address());
    }
    return new Ambiguous<>(List.copyOf(distinct.values()));
  }

  /**
   * Suggests completions for the token currently being typed (the last element of {@code args}, or
   * a fresh token when {@code args} is empty).
   *
   * @param roots the destination-tree roots from every provider
   * @param args the arguments typed so far; the last is treated as a partial prefix
   * @param hasPermission tests whether the player holds a permission node
   * @param <W> the world type
   * @param <V> the vector type
   * @return the candidate tokens, alphabetically
   */
  public static <W, V> List<String> suggest(
      Map<String, ? extends PlatformDestinationTree<W, V>> roots,
      List<String> args,
      Predicate<String> hasPermission) {
    return suggest(roots, args, hasPermission, address -> true);
  }

  /**
   * Suggests completions, also honoring the Cobblestone navigation-gate permission.
   *
   * @param roots the destination-tree roots from every provider
   * @param args the arguments typed so far; the last is treated as a partial prefix
   * @param hasPermission tests whether the player holds a permission node
   * @param canNavigate tests whether the player may navigate to a given canonical address
   * @param <W> the world type
   * @param <V> the vector type
   * @return the candidate tokens, alphabetically
   */
  public static <W, V> List<String> suggest(
      Map<String, ? extends PlatformDestinationTree<W, V>> roots,
      List<String> args,
      Predicate<String> hasPermission,
      Predicate<List<String>> canNavigate) {
    int index = args.isEmpty() ? 0 : args.size() - 1;
    List<String> prefix = args.isEmpty() ? List.of() : args.subList(0, index);
    String typed = args.isEmpty() ? "" : args.get(index);
    String partial = typed.toLowerCase(Locale.ROOT);
    Access<W, V> access = new Access<>(hasPermission, canNavigate, typed);
    Candidates candidates = new Candidates();
    for (var root : roots.entrySet()) {
      suggestFrom(root.getKey(), root.getValue(), prefix, 0, new ArrayList<>(), candidates, access);
    }
    return candidates.tokens(partial);
  }

  // -----------------------------------------------------------------------------------------
  // Resolution
  // -----------------------------------------------------------------------------------------

  /**
   * Matches {@code args} from index {@code ai} against {@code node}, whose own key has not yet been
   * accounted for: it may be consumed (the player typed it) or, unless the node is strict, omitted.
   * Either way the node's key joins the canonical trail.
   */
  private static <W, V> void match(
      String key,
      PlatformDestinationTree<W, V> node,
      List<String> args,
      int ai,
      List<String> trail,
      Results<W, V> out) {
    trail.add(key);
    if (ai < args.size() && args.get(ai).equalsIgnoreCase(key)) {
      matchChildren(node, args, ai + 1, trail, out);
    }
    if (!node.strict()) {
      matchChildren(node, args, ai, trail, out);
    }
    trail.removeLast();
  }

  /** Matches the remaining args against {@code node}'s children; {@code trail} ends at the node. */
  private static <W, V> void matchChildren(
      PlatformDestinationTree<W, V> node,
      List<String> args,
      int ai,
      List<String> trail,
      Results<W, V> out) {
    if (ai >= args.size()) {
      return; // nothing left to match, and a destination's own key is never optional
    }
    // A destination is named by its exact key, followed by nothing but its options — so at most
    // one destination per node is ever materialized.
    String token = args.get(ai);
    for (Map.Entry<String, Supplier<MinecraftDestination<W, V>>> entry :
        node.destinations().entrySet()) {
      if (!entry.getKey().equalsIgnoreCase(token)) {
        continue;
      }
      trail.add(entry.getKey());
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      MinecraftDestination<W, V> destination = entry.getValue().get();
      if (out.allows(address, destination)) {
        applyOptions(address, destination, args.subList(ai + 1, args.size()), out);
      }
    }
    // Sub-trees are still worth descending on the same token: the child's own key may be omitted,
    // leaving that token to name a destination inside it.
    for (var child : node.subTrees().entrySet()) {
      match(child.getKey(), child.getValue().get(), args, ai, trail, out);
    }
  }

  /**
   * Fits the tokens after a matched destination's key to its options: a match if they all parse, an
   * {@link InvalidOptions} if the destination takes options but these do not fit, and nothing at
   * all if it takes none (the tokens must be meant for something else).
   */
  private static <W, V> void applyOptions(
      List<String> address,
      MinecraftDestination<W, V> destination,
      List<String> tokens,
      Results<W, V> out) {
    List<DestinationOption<?>> options = destination.options();
    if (options.isEmpty()) {
      if (tokens.isEmpty()) {
        out.matches.add(new Match<>(address, destination));
      }
      return;
    }
    long required = options.stream().filter(DestinationOption::required).count();
    if (tokens.size() < required || tokens.size() > options.size()) {
      out.invalid.add(new InvalidOptions<>(address, options, null));
      return;
    }
    Map<DestinationOption<?>, Object> values = new IdentityHashMap<>();
    for (int i = 0; i < tokens.size(); i++) {
      Optional<?> value = options.get(i).parse(tokens.get(i));
      if (value.isEmpty()) {
        out.invalid.add(new InvalidOptions<>(address, options, tokens.get(i)));
        return;
      }
      values.put(options.get(i), value.get());
    }
    out.matches.add(new Match<>(address, destination.configure(OptionValues.of(values))));
  }

  /** One destination matched by the typed args, with the canonical address it is filed under. */
  private record Match<W, V>(List<String> address, MinecraftDestination<W, V> destination) {}

  /** What a resolution walk has found so far, and the permission gates it applies. */
  private static final class Results<W, V> {
    private final List<Match<W, V>> matches = new ArrayList<>();
    private final List<InvalidOptions<W, V>> invalid = new ArrayList<>();
    private final Predicate<String> hasPermission;
    private final Predicate<List<String>> canNavigate;

    Results(Predicate<String> hasPermission, Predicate<List<String>> canNavigate) {
      this.hasPermission = hasPermission;
      this.canNavigate = canNavigate;
    }

    boolean allows(List<String> address, MinecraftDestination<W, V> destination) {
      return hasAll(hasPermission, destination.permissions()) && canNavigate.test(address);
    }
  }

  // -----------------------------------------------------------------------------------------
  // Suggestion
  // -----------------------------------------------------------------------------------------

  /**
   * The two permission gates, carried together through the suggestion walk, along with the token
   * being completed (as typed) for options that want it.
   */
  private record Access<W, V>(
      Predicate<String> hasPermission, Predicate<List<String>> canNavigate, String partial) {

    boolean allows(List<String> address, MinecraftDestination<W, V> destination) {
      return hasAll(hasPermission, destination.permissions()) && canNavigate.test(address);
    }
  }

  /**
   * Walks {@code node}, whose own key has not yet been accounted for, against the typed prefix.
   * Once the prefix runs out the node's key is itself a candidate, and — if the node may be
   * promoted past — so is everything its children contribute.
   */
  private static <W, V> void suggestFrom(
      String key,
      PlatformDestinationTree<W, V> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<W, V> access) {
    trail.add(key);
    if (pi < prefix.size()) {
      if (prefix.get(pi).equalsIgnoreCase(key)) {
        suggestChildren(node, prefix, pi + 1, trail, out, access);
      }
      if (!node.strict()) {
        suggestChildren(node, prefix, pi, trail, out, access);
      }
    } else {
      // Promoting past this node means offering everything below it — worth it only for a small
      // subtree, so an oversized one falls back to "type my key first".
      Candidates promoted = node.strict() ? null : collect(node, trail, access, PROMOTION_LIMIT);
      out.node(key, extendable(node, promoted));
      if (promoted != null) {
        out.merge(promoted);
      }
    }
    trail.removeLast();
  }

  /** Continues the prefix walk into {@code node}'s children; {@code trail} ends at the node. */
  private static <W, V> void suggestChildren(
      PlatformDestinationTree<W, V> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<W, V> access) {
    if (pi == prefix.size()) {
      // The player has typed their way to this node, so its children are what comes next — no limit
      // applies to a level that was asked for by name.
      Candidates children = collect(node, trail, access, Integer.MAX_VALUE);
      if (children != null) {
        out.merge(children);
      }
      return;
    }
    // A destination with options may already have been named, and the player is now typing them.
    suggestOptions(node, prefix, pi, trail, out, access);
    for (var child : node.subTrees().entrySet()) {
      suggestFrom(child.getKey(), child.getValue().get(), prefix, pi, trail, out, access);
    }
  }

  /**
   * If {@code prefix[pi]} names one of {@code node}'s destinations that takes options, and every
   * option token typed after it so far is valid, offers the candidates of the option being typed.
   */
  private static <W, V> void suggestOptions(
      PlatformDestinationTree<W, V> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<W, V> access) {
    String key = prefix.get(pi);
    List<String> typedOptions = prefix.subList(pi + 1, prefix.size());
    for (Map.Entry<String, Supplier<MinecraftDestination<W, V>>> entry :
        node.destinations().entrySet()) {
      if (!entry.getKey().equalsIgnoreCase(key)) {
        continue;
      }
      MinecraftDestination<W, V> destination = entry.getValue().get();
      List<DestinationOption<?>> options = destination.options();
      if (typedOptions.size() >= options.size()) {
        continue; // takes no options, or all of them have been typed
      }
      trail.add(entry.getKey());
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      if (!access.allows(address, destination) || !allParse(options, typedOptions)) {
        continue;
      }
      for (String candidate : options.get(typedOptions.size()).suggest(access.partial())) {
        out.node(candidate, true);
      }
    }
  }

  private static boolean allParse(List<DestinationOption<?>> options, List<String> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      if (options.get(i).parse(tokens.get(i)).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * The tokens that may directly follow {@code node} in a command: its destinations' keys, its
   * sub-trees' keys, and — for each sub-tree small enough to promote past — that sub-tree's own
   * contribution.
   *
   * @return the candidates, or {@code null} if there turned out to be more than {@code limit} of
   *     them (in which case the walk stopped early)
   */
  private static <W, V> Candidates collect(
      PlatformDestinationTree<W, V> node, List<String> trail, Access<W, V> access, int limit) {
    Candidates out = new Candidates();
    for (Map.Entry<String, Supplier<MinecraftDestination<W, V>>> entry :
        node.destinations().entrySet()) {
      trail.add(entry.getKey());
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      MinecraftDestination<W, V> destination = entry.getValue().get();
      if (!access.allows(address, destination)) {
        continue;
      }
      List<DestinationOption<?>> options = destination.options();
      if (options.isEmpty() || !options.getFirst().required()) {
        out.destination(entry.getKey()); // complete as typed
      }
      if (!options.isEmpty()) {
        out.node(entry.getKey(), true); // and options may follow
      }
      if (out.size() > limit) {
        return null;
      }
    }
    for (var supplier : node.subTrees().entrySet()) {
      var key = supplier.getKey();
      PlatformDestinationTree<W, V> child = supplier.getValue().get();
      Candidates promoted = null;
      if (!child.strict()) { // a strict child's key is mandatory: nothing below it belongs here
        trail.add(key);
        promoted = collect(child, trail, access, PROMOTION_LIMIT);
        trail.removeLast();
      }
      out.node(key, extendable(child, promoted));
      if (out.size() > limit) {
        return null;
      }
      // Injecting a child's own contribution here is a bonus, so an oversized one is simply left
      // out — the child's key stays, and the player reaches its contents by naming it. Only the
      // children themselves (above) can push a level past the limit.
      if (promoted != null && out.size() + promoted.size() <= limit) {
        out.merge(promoted);
      }
    }
    return out;
  }

  /**
   * Whether naming {@code node} gets the player anywhere. When its contents were gathered, that is
   * simply whether anything came back — a level whose every destination is hidden from this player
   * is a dead end and should not be offered. A strict level is never gathered (that is the point of
   * marking it), so its structure is all there is to go on.
   */
  private static boolean extendable(PlatformDestinationTree<?, ?> node, Candidates gathered) {
    if (node.strict() || gathered == null) {
      return !node.subTrees().isEmpty() || !node.destinations().isEmpty();
    }
    return gathered.size() > 0;
  }

  /**
   * The candidate tokens for one position in the command, deduplicated case-insensitively (the
   * first spelling seen wins, matching being case-insensitive anyway).
   */
  private static final class Candidates {

    private final Map<String, Candidate> byKey = new LinkedHashMap<>();

    /** Records a token that completes an address here. */
    void destination(String key) {
      candidate(key).terminals++;
    }

    /** Records a token that names a level here, and whether anything lies below it. */
    void node(String key, boolean extendable) {
      candidate(key).extendable |= extendable;
    }

    void merge(Candidates other) {
      other
          .byKey
          .values()
          .forEach(
              candidate -> {
                Candidate mine = candidate(candidate.display);
                mine.terminals += candidate.terminals;
                mine.extendable |= candidate.extendable;
              });
    }

    int size() {
      return byKey.size();
    }

    private Candidate candidate(String key) {
      return byKey.computeIfAbsent(key.toLowerCase(Locale.ROOT), ignored -> new Candidate(key));
    }

    /** The offerable tokens starting with {@code partial} (already lower-cased), alphabetically. */
    List<String> tokens(String partial) {
      List<String> out = new ArrayList<>();
      byKey.forEach(
          (key, candidate) -> {
            if (!key.startsWith(partial)) {
              return;
            }
            // A token that completes more than one address, and leads nowhere further, could only
            // ever produce an "ambiguous" error — so it is not worth offering.
            if (candidate.extendable || candidate.terminals == 1) {
              out.add(candidate.display);
            }
          });
      out.sort(String.CASE_INSENSITIVE_ORDER);
      return List.copyOf(out);
    }
  }

  /** One candidate token: how many addresses it completes here, and whether it can be extended. */
  private static final class Candidate {
    private final String display;
    private int terminals;
    private boolean extendable;

    Candidate(String display) {
      this.display = display;
    }
  }

  // -----------------------------------------------------------------------------------------
  // Shared
  // -----------------------------------------------------------------------------------------

  private static boolean hasAll(Predicate<String> hasPermission, List<String> permissions) {
    for (String permission : permissions) {
      if (!hasPermission.test(permission)) {
        return false;
      }
    }
    return true;
  }
}
