/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.HashMap;
import java.util.Map;
import org.cobblestonemc.api.TraversalState;

/**
 * Prices the Tier-1 legs the search has not solved yet, learning as it goes from the ones it has.
 *
 * <p><b>The problem.</b> A leg's only cheap estimate is straight-line distance times the cheapest
 * per-block cost the agent could manage — admissible, and wrong by a wide margin, because a real
 * route winds and the cheapest mode is rarely the one taken. Every leg therefore comes back several
 * times dearer than promised, which makes some other unexplored route look cheaper, so Tier-1
 * re-plans and works through the alternatives one Tier-2 solve at a time. Those solves run into
 * seconds; spending several to discover that the first route was fine is the single most expensive
 * thing this search does.
 *
 * <p><b>The fix is to measure rather than guess.</b> Every solved leg hands back a true cost
 * alongside the estimate it was given, and their ratio is exactly how optimistic the bound was on
 * <i>this</i> terrain, for <i>this</i> agent's modes. Fold that ratio back into the remaining legs
 * and the estimate stops being systematically optimistic after the very first solve — so the route
 * Tier-1 committed to has to be genuinely beaten, not merely beaten by a promise nothing can keep.
 *
 * <p><b>Ratios, not costs per block.</b> The two are the same number: multiply a ratio by the
 * agent's cheapest cost per block and you have that world's measured average cost per block.
 * Keeping it dimensionless means this class never needs to know what that constant is — it corrects
 * whatever the configured {@link HeuristicStrategy} says, including a test's zero heuristic, which
 * it leaves at zero.
 *
 * <p><b>Per world, and the minimum stands in for the unsampled ones.</b> A block of nether is not a
 * block of overworld, and lumping the two together would misprice both. But a world nothing has
 * solved in yet has to be priced somehow, and the least pessimistic sample is the right stand-in:
 * it keeps an unvisited world from being ruled out on a guess, at the cost of Tier-1 sometimes
 * committing to a route across a portal that a sample would have talked it out of. That mistake
 * costs one Tier-2 solve and then corrects itself, because that solve is the sample.
 *
 * <p>State is per search, and mutated only from the search's own step, which runs one at a time.
 */
final class Tier1Estimator {

  /**
   * How far past its estimate a single leg is allowed to drag its world's factor.
   *
   * <p>One pathological leg — a long dig, a route that doubled back around a mountain range — would
   * otherwise price every remaining leg in that world so high that nothing could compete with what
   * is already solved, which is the thrash this class exists to prevent, only inverted.
   */
  private static final double MAX_FACTOR = 20.0;

  /**
   * The shortest leg worth learning from, in blocks. Over a few blocks the ratio is dominated by
   * whatever the first step happened to cost, and a leg whose target region contains its own start
   * has an estimate of zero and no ratio at all.
   */
  private static final double MIN_SAMPLE_DISTANCE = 8.0;

  private final HeuristicStrategy admissible;
  private final double pessimism;

  /** Per world: the legs solved in it so far, as summed true cost over summed estimate. */
  private final Map<Domain, Samples> byDomain = new HashMap<>();

  /** The least pessimistic factor any world has shown, or 1.0 while none has. */
  private double fallbackFactor = 1.0;

  Tier1Estimator(HeuristicStrategy admissible, double pessimism) {
    this.admissible = admissible;
    this.pessimism = pessimism;
  }

  /**
   * The cost to assume for a leg that has not been solved: the admissible estimate, inflated by
   * what this world's solved legs actually cost, and by the configured pessimism margin on top.
   *
   * @param from the cell the leg starts at
   * @param target the region the leg ends in
   * @param state the traversal state the leg starts in
   * @return the assumed cost, in seconds
   */
  double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
    return admissible.estimate(from, target, state) * factorFor(target.domain()) * pessimism;
  }

  /**
   * Records what a leg actually cost, so the remaining legs in its world are priced by it.
   *
   * @param from the cell the solved leg started at
   * @param target the region it ended in
   * @param state the traversal state it started in
   * @param trueCost what Tier-2 found it to cost, in seconds
   */
  void observe(Cell from, DomainRegion<?> target, TraversalState state, double trueCost) {
    if (!(trueCost > 0)) {
      return; // a leg that cost nothing teaches nothing
    }
    if (from.distance(target.nearestBoundaryCell(from)) < MIN_SAMPLE_DISTANCE) {
      return;
    }
    double estimated = admissible.estimate(from, target, state);
    if (!(estimated > 0)) {
      return; // no bound to measure against (the zero heuristic, or a degenerate leg)
    }
    byDomain.computeIfAbsent(target.domain(), key -> new Samples()).add(estimated, trueCost);
    recomputeFallback();
  }

  /** The inflation factor to price an unsolved leg in {@code domain} with; never below 1. */
  private double factorFor(Domain domain) {
    Samples samples = byDomain.get(domain);
    return samples == null ? fallbackFactor : samples.factor();
  }

  private void recomputeFallback() {
    double lowest = Double.POSITIVE_INFINITY;
    for (Samples samples : byDomain.values()) {
      lowest = Math.min(lowest, samples.factor());
    }
    fallbackFactor = lowest == Double.POSITIVE_INFINITY ? 1.0 : lowest;
  }

  /**
   * One world's solved legs. Summing both sides rather than averaging each leg's own ratio weights
   * a sample by how much travelling it represents, so one short leg through a cave does not outvote
   * a thousand blocks of open ground.
   */
  private static final class Samples {
    private double estimated;
    private double actual;

    void add(double legEstimate, double legCost) {
      estimated += legEstimate;
      actual += legCost;
    }

    /**
     * How much dearer this world has turned out than the admissible bound. Floored at 1, since the
     * bound is a lower bound and a factor below it would only make the estimate more optimistic
     * than a bound already known to be too optimistic.
     */
    double factor() {
      return Math.min(MAX_FACTOR, Math.max(1.0, actual / estimated));
    }
  }
}
