/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;

/**
 * Turns the arguments a player typed into a concrete {@link MinecraftDestination}, traversing the
 * {@link DestinationTree}s gathered from every registered provider and applying <b>name promotion</b>:
 * a level whose node is not {@code strict} may be omitted, so {@code /nav home} resolves when only one
 * provider offers {@code home}, while an ambiguous name forces the fuller path ({@code /nav essentials
 * home}). Strict levels can never be omitted. The same traversal powers tab-completion via
 * {@link #suggest}.
 *
 * <p>This is platform-neutral: callers gather the provider roots (on Paper, from the {@code
 * ServicesManager}) and pass a permission predicate; the resolver never touches a server type.
 *
 * <p><b>Note:</b> resolution currently materializes every reachable node (it calls each child
 * supplier). That is fine for the shallow trees shipped so far; a provider exposing very large sets
 * (every town, every player home) will want a lazier, index-guided traversal — tracked for a later
 * pass.
 */
public final class DestinationResolver {

  private DestinationResolver() {
  }

  /** The outcome of resolving typed arguments against the destination forest. */
  public sealed interface Resolution<W, V> {
  }

  /**
   * Exactly one destination matched.
   *
   * @param destination the resolved destination
   * @param address the full key path that identifies it (for confirmation messages)
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record Resolved<W, V>(MinecraftDestination<W, V> destination, List<String> address)
      implements Resolution<W, V> {
  }

  /**
   * More than one destination matched; the player must disambiguate with a fuller path.
   *
   * @param addresses the full key paths of the candidates
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record Ambiguous<W, V>(List<List<String>> addresses) implements Resolution<W, V> {
  }

  /**
   * No destination matched (or none the player may use).
   *
   * @param <W> the world type
   * @param <V> the vector type
   */
  public record NotFound<W, V>() implements Resolution<W, V> {
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
      List<? extends DestinationTree<W, V>> roots, List<String> args, Predicate<String> hasPermission) {
    List<Address<W, V>> matched = new ArrayList<>();
    for (Address<W, V> address : addresses(roots, hasPermission)) {
      if (address.sequences().stream().anyMatch(sequence -> sequence.equals(args))) {
        matched.add(address);
      }
    }
    if (matched.isEmpty()) {
      return new NotFound<>();
    }
    if (matched.size() == 1) {
      return new Resolved<>(matched.get(0).destination(), matched.get(0).tokens());
    }
    return new Ambiguous<>(matched.stream().map(Address::tokens).toList());
  }

  /**
   * Suggests completions for the token currently being typed (the last element of {@code args}, or a
   * fresh token when {@code args} is empty). Honors promotion, so both a promoted leaf name and the
   * fuller path are offered.
   *
   * @param roots the destination-tree roots from every provider
   * @param args the arguments typed so far; the last is treated as a partial prefix
   * @param hasPermission tests whether the player holds a permission node
   * @param <W> the world type
   * @param <V> the vector type
   * @return the distinct candidate tokens, in first-seen order
   */
  public static <W, V> List<String> suggest(
      List<? extends DestinationTree<W, V>> roots, List<String> args, Predicate<String> hasPermission) {
    int index = args.isEmpty() ? 0 : args.size() - 1;
    List<String> prefix = args.isEmpty() ? List.of() : args.subList(0, index);
    String partial = args.isEmpty() ? "" : args.get(index);
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (Address<W, V> address : addresses(roots, hasPermission)) {
      for (List<String> sequence : address.sequences()) {
        if (sequence.size() <= index || !sequence.subList(0, index).equals(prefix)) {
          continue;
        }
        String candidate = sequence.get(index);
        if (candidate.startsWith(partial)) {
          out.add(candidate);
        }
      }
    }
    return List.copyOf(out);
  }

  private static <W, V> List<Address<W, V>> addresses(
      List<? extends DestinationTree<W, V>> roots, Predicate<String> hasPermission) {
    List<Address<W, V>> out = new ArrayList<>();
    for (DestinationTree<W, V> root : roots) {
      collect(root, new ArrayList<>(), new ArrayList<>(), out, hasPermission);
    }
    return out;
  }

  private static <W, V> void collect(
      DestinationTree<W, V> node,
      List<String> keyTrail,
      List<Boolean> strictTrail,
      List<Address<W, V>> out,
      Predicate<String> hasPermission) {
    keyTrail.add(node.key());
    strictTrail.add(node.strict());
    node.destinations().forEach((key, supplier) -> {
      MinecraftDestination<W, V> destination = supplier.get();
      if (hasAll(hasPermission, destination.permissions())) {
        List<String> tokens = new ArrayList<>(keyTrail);
        tokens.add(key);
        boolean[] required = new boolean[tokens.size()];
        for (int i = 0; i < strictTrail.size(); i++) {
          required[i] = strictTrail.get(i);
        }
        required[required.length - 1] = true; // the leaf name is always required
        out.add(new Address<>(tokens, required, destination));
      }
    });
    node.subTrees().forEach((key, supplier) ->
        collect(supplier.get(), keyTrail, strictTrail, out, hasPermission));
    keyTrail.remove(keyTrail.size() - 1);
    strictTrail.remove(strictTrail.size() - 1);
  }

  private static boolean hasAll(Predicate<String> hasPermission, List<String> permissions) {
    for (String permission : permissions) {
      if (!hasPermission.test(permission)) {
        return false;
      }
    }
    return true;
  }

  /** A full path to one destination, plus which tokens are required (non-promotable). */
  private record Address<W, V>(
      List<String> tokens, boolean[] required, MinecraftDestination<W, V> destination) {

    /** All input token sequences that address this destination, choosing keep/drop on optionals. */
    List<List<String>> sequences() {
      List<List<String>> results = new ArrayList<>();
      build(0, new ArrayList<>(), results);
      return results;
    }

    private void build(int i, List<String> acc, List<List<String>> out) {
      if (i == tokens.size()) {
        out.add(List.copyOf(acc));
        return;
      }
      if (!required[i]) {
        build(i + 1, acc, out); // omit this (promotable) token
      }
      acc.add(tokens.get(i));
      build(i + 1, acc, out); // include it
      acc.remove(acc.size() - 1);
    }
  }
}
