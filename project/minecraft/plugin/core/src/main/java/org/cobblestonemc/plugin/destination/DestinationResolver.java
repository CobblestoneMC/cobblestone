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
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.OptionValues;

/**
 * Turns the arguments a player typed into the {@link DestinationLeaf} they picked, walking the
 * {@link DestinationNode}s gathered from every registered provider.
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
 *   <li>a node's own key is always required when the node is {@linkplain DestinationNode#strict()
 *       strict}, and
 *   <li>the destination's own key is always required.
 * </ul>
 *
 * <h2>Options</h2>
 *
 * <p>A destination may take {@linkplain DestinationLeaf#options() options} — tokens typed after its
 * key, such as the {@code 100 64 -200} of {@code coordinates 100 64 -200}. Options are never part
 * of the address and are never promoted: the key comes first, then one token per option, in order.
 * A destination with no options must be the last token. When a destination's key matches but the
 * tokens after it do not fit its options, and nothing else matches, the result is {@link
 * InvalidOptions} so the player can be shown how to use it.
 *
 * <p>A level may also give a destination {@linkplain DestinationNode#aliases() aliases}: other
 * names a player might guess, such as {@code horse} for {@code mount}. An alias stands in for the
 * destination's own key when typed, and the destination is still addressed by its key. Aliases are
 * never suggested.
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
  public sealed interface Resolution<D> {}

  /**
   * Exactly one destination matched. It is not built yet: that is up to the caller, once it is on
   * the right thread to {@linkplain DestinationLeaf#build build} it.
   *
   * @param leaf the leaf that matched
   * @param values its parsed options
   * @param address the canonical key path that identifies it (for confirmation messages)
   * @param optionTokens the tokens its options were parsed from, as typed — with the address, all
   *     it takes to name the same destination again
   * @param <D> what the leaves build
   */
  public record Resolved<D>(
      DestinationLeaf<D> leaf, OptionValues values, List<String> address, List<String> optionTokens)
      implements Resolution<D> {
    /** Canonical constructor; copies the lists. */
    public Resolved {
      address = List.copyOf(address);
      optionTokens = List.copyOf(optionTokens);
    }
  }

  /**
   * More than one destination matched; the player must disambiguate with a fuller path.
   *
   * @param addresses the distinct canonical key paths of the candidates, alphabetically
   * @param <D> what the leaves build
   */
  public record Ambiguous<D>(List<List<String>> addresses) implements Resolution<D> {}

  /**
   * No destination matched (or none the player may use).
   *
   * @param <D> what the leaves build
   */
  public record NotFound<D>() implements Resolution<D> {}

  /**
   * A destination's key matched, but the tokens after it do not fit its options — too few, too
   * many, or one that does not parse — and nothing else matched.
   *
   * @param address the canonical key path of the destination
   * @param options the destination's options, for a usage hint (see {@link #usage})
   * @param invalidToken the first token that did not parse, or {@code null} if the problem was the
   *     number of tokens
   * @param <D> what the leaves build
   */
  public record InvalidOptions<D>(
      List<String> address, List<DestinationOption<?>> options, String invalidToken)
      implements Resolution<D> {}

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
   * @param <D> what the leaves build
   * @return the resolution
   */
  public static <D> Resolution<D> resolve(
      Map<String, ? extends DestinationNode<D>> roots,
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
   * @param <D> what the leaves build
   * @return the resolution
   */
  public static <D> Resolution<D> resolve(
      Map<String, ? extends DestinationNode<D>> roots,
      List<String> args,
      Predicate<String> hasPermission,
      Predicate<List<String>> canNavigate) {
    if (args.isEmpty()) {
      return new NotFound<>();
    }
    Results<D> results = new Results<>(hasPermission, canNavigate);
    for (var root : roots.entrySet()) {
      match(root.getKey(), root.getValue(), args, 0, new ArrayList<>(), results);
    }
    List<Resolved<D>> matches = results.matches;
    if (matches.isEmpty()) {
      return results.invalid.isEmpty() ? new NotFound<>() : results.invalid.getFirst();
    }
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    // Distinct canonical addresses, first spelling wins. Sorted, so what the player is shown does
    // not depend on the order the roots happened to be iterated in.
    Map<String, List<String>> distinct = new TreeMap<>();
    for (Resolved<D> candidate : matches) {
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
   * @param <D> what the leaves build
   * @return the candidate tokens, alphabetically
   */
  public static <D> List<String> suggest(
      Map<String, ? extends DestinationNode<D>> roots,
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
   * @param <D> what the leaves build
   * @return the candidate tokens, alphabetically
   */
  public static <D> List<String> suggest(
      Map<String, ? extends DestinationNode<D>> roots,
      List<String> args,
      Predicate<String> hasPermission,
      Predicate<List<String>> canNavigate) {
    int index = args.isEmpty() ? 0 : args.size() - 1;
    List<String> prefix = args.isEmpty() ? List.of() : args.subList(0, index);
    String typed = args.isEmpty() ? "" : args.get(index);
    String partial = typed.toLowerCase(Locale.ROOT);
    Access<D> access = new Access<>(hasPermission, canNavigate, typed);
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
  private static <D> void match(
      String key,
      DestinationNode<D> node,
      List<String> args,
      int ai,
      List<String> trail,
      Results<D> out) {
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
  private static <D> void matchChildren(
      DestinationNode<D> node, List<String> args, int ai, List<String> trail, Results<D> out) {
    if (ai >= args.size()) {
      return; // nothing left to match, and a destination's own key is never optional
    }
    // A destination is named by its exact key (or an alias), followed by nothing but its options.
    for (String leafKey : destinationsNamed(node, args.get(ai))) {
      trail.add(leafKey);
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      DestinationLeaf<D> leaf = destinationOf(node, leafKey);
      if (out.allows(address, leaf)) {
        applyOptions(address, leaf, args.subList(ai + 1, args.size()), out);
      }
    }
    // Levels are still worth descending on the same token: the child's own key may be omitted,
    // leaving that token to name a destination inside it.
    for (var child : levels(node)) {
      match(child.getKey(), child.getValue().get(), args, ai, trail, out);
    }
  }

  /**
   * Fits the tokens after a matched destination's key to its options: a match if they all parse, an
   * {@link InvalidOptions} if the destination takes options but these do not fit, and nothing at
   * all if it takes none (the tokens must be meant for something else).
   */
  private static <D> void applyOptions(
      List<String> address, DestinationLeaf<D> leaf, List<String> tokens, Results<D> out) {
    if (leaf.options().isEmpty() && !tokens.isEmpty()) {
      return;
    }
    switch (options(address, leaf, tokens)) {
      case Resolved<D> resolved -> out.matches.add(resolved);
      case InvalidOptions<D> invalid -> out.invalid.add(invalid);
      default -> {}
    }
  }

  /**
   * Fits option tokens to a destination already chosen by other means, exactly as {@link #resolve}
   * would after its key.
   *
   * @param address the destination's canonical address
   * @param leaf the destination
   * @param tokens one token per option, in order (optional ones may be left off the end)
   * @param <D> what the leaf builds
   * @return {@link Resolved} if the tokens fit, otherwise {@link InvalidOptions}
   */
  public static <D> Resolution<D> options(
      List<String> address, DestinationLeaf<D> leaf, List<String> tokens) {
    List<DestinationOption<?>> options = leaf.options();
    long required = options.stream().filter(DestinationOption::required).count();
    if (tokens.size() < required || tokens.size() > options.size()) {
      return new InvalidOptions<>(address, options, null);
    }
    Map<DestinationOption<?>, Object> values = new IdentityHashMap<>();
    for (int i = 0; i < tokens.size(); i++) {
      Optional<?> value = options.get(i).parse(tokens.get(i));
      if (value.isEmpty()) {
        return new InvalidOptions<>(address, options, tokens.get(i));
      }
      values.put(options.get(i), value.get());
    }
    OptionValues parsed = values.isEmpty() ? OptionValues.empty() : OptionValues.of(values);
    return new Resolved<>(leaf, parsed, address, tokens);
  }

  /** What a resolution walk has found so far, and the permission gates it applies. */
  private static final class Results<D> {
    private final List<Resolved<D>> matches = new ArrayList<>();
    private final List<InvalidOptions<D>> invalid = new ArrayList<>();
    private final Predicate<String> hasPermission;
    private final Predicate<List<String>> canNavigate;

    Results(Predicate<String> hasPermission, Predicate<List<String>> canNavigate) {
      this.hasPermission = hasPermission;
      this.canNavigate = canNavigate;
    }

    boolean allows(List<String> address, DestinationLeaf<D> leaf) {
      return hasAll(hasPermission, leaf.permissions()) && canNavigate.test(address);
    }
  }

  // -----------------------------------------------------------------------------------------
  // Suggestion
  // -----------------------------------------------------------------------------------------

  /**
   * The two permission gates, carried together through the suggestion walk, along with the token
   * being completed (as typed) for options that want it.
   */
  private record Access<D>(
      Predicate<String> hasPermission, Predicate<List<String>> canNavigate, String partial) {

    boolean allows(List<String> address, DestinationLeaf<D> leaf) {
      return hasAll(hasPermission, leaf.permissions()) && canNavigate.test(address);
    }
  }

  /**
   * Walks {@code node}, whose own key has not yet been accounted for, against the typed prefix.
   * Once the prefix runs out the node's key is itself a candidate, and — if the node may be
   * promoted past — so is everything its children contribute.
   */
  private static <D> void suggestFrom(
      String key,
      DestinationNode<D> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<D> access) {
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
  private static <D> void suggestChildren(
      DestinationNode<D> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<D> access) {
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
    for (var child : levels(node)) {
      suggestFrom(child.getKey(), child.getValue().get(), prefix, pi, trail, out, access);
    }
  }

  /**
   * If {@code prefix[pi]} names one of {@code node}'s destinations that takes options, and every
   * option token typed after it so far is valid, offers the candidates of the option being typed.
   */
  private static <D> void suggestOptions(
      DestinationNode<D> node,
      List<String> prefix,
      int pi,
      List<String> trail,
      Candidates out,
      Access<D> access) {
    List<String> typedOptions = prefix.subList(pi + 1, prefix.size());
    for (String leafKey : destinationsNamed(node, prefix.get(pi))) {
      DestinationLeaf<D> leaf = destinationOf(node, leafKey);
      List<DestinationOption<?>> options = leaf.options();
      if (typedOptions.size() >= options.size()) {
        continue; // takes no options, or all of them have been typed
      }
      trail.add(leafKey);
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      if (!access.allows(address, leaf) || !allParse(options, typedOptions)) {
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
  private static <D> Candidates collect(
      DestinationNode<D> node, List<String> trail, Access<D> access, int limit) {
    Candidates out = new Candidates();
    for (Map.Entry<String, DestinationChild<D>> entry : node.children().entrySet()) {
      if (entry.getValue().destination().isEmpty()) {
        continue;
      }
      trail.add(entry.getKey());
      List<String> address = List.copyOf(trail);
      trail.removeLast();
      DestinationLeaf<D> leaf = entry.getValue().destination().get();
      if (!access.allows(address, leaf)) {
        continue;
      }
      List<DestinationOption<?>> options = leaf.options();
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
    for (var supplier : levels(node)) {
      var key = supplier.getKey();
      DestinationNode<D> child = supplier.getValue().get();
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
  private static boolean extendable(DestinationNode<?> node, Candidates gathered) {
    if (node.strict() || gathered == null) {
      return !node.children().isEmpty();
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

  /** The keys of {@code node}'s destinations that {@code token} names, by key or by alias. */
  private static List<String> destinationsNamed(DestinationNode<?> node, String token) {
    List<String> keys = new ArrayList<>();
    node.children()
        .forEach(
            (key, child) -> {
              if (key.equalsIgnoreCase(token) && child.destination().isPresent()) {
                keys.add(key);
              }
            });
    node.aliases()
        .forEach(
            (alias, key) -> {
              if (alias.equalsIgnoreCase(token)
                  && destinationOf(node, key) != null
                  && !keys.contains(key)) {
                keys.add(key);
              }
            });
    return keys;
  }

  /** The destination of {@code node}'s entry under {@code key}, or {@code null} if it has none. */
  private static <D> DestinationLeaf<D> destinationOf(DestinationNode<D> node, String key) {
    DestinationChild<D> child = node.children().get(key);
    return child == null ? null : child.destination().orElse(null);
  }

  /**
   * {@code node}'s entries that have a level beneath them, each with the supplier of that level.
   */
  private static <D> List<Map.Entry<String, Supplier<? extends DestinationNode<D>>>> levels(
      DestinationNode<D> node) {
    List<Map.Entry<String, Supplier<? extends DestinationNode<D>>>> levels = new ArrayList<>();
    node.children()
        .forEach(
            (key, child) -> child.children().ifPresent(level -> levels.add(Map.entry(key, level))));
    return levels;
  }

  private static boolean hasAll(Predicate<String> hasPermission, List<String> permissions) {
    for (String permission : permissions) {
      if (!hasPermission.test(permission)) {
        return false;
      }
    }
    return true;
  }
}
