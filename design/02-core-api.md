# Odyssey — `core-api`

The pure, Minecraft-agnostic contract. No third-party dependencies. Package
`net.whimxiqal.odyssey.api`. Everything here is designed so `core` can implement the algorithms
and so any embedder (Minecraft or otherwise) can drive navigation over abstract space.

> Stubs show intent, not final formatting. Costs are `double` (seconds). Four generic parameters
> flow through the whole search so downstream code never downcasts:
> - **`A extends Agent`** — the agent type.
> - **`T extends Enum<T>`** — the `StepType` enum.
> - **`I`** — the caller-supplied `Instruction` payload type (unbounded; `Void` when unused).
> - **`D extends Domain`** — the domain type (e.g. `OdysseyWorld`, `TestWorld`).
>
> `A`, `T`, `I`, `D` are each **singular over a whole search**: one agent, one step-type enum, one
> instruction type, and **one domain type**. Different worlds (Overworld, Nether) are different
> *instances* of the same `D`, so `Step.domain()` returns a concrete `OdysseyWorld` with no lookup or
> cast. **Contract:** an embedder must use exactly one concrete `Domain` implementation and
> distinguish dimensions with a field/method (`environment()`), never with subtypes — subclassing
> `Domain` per dimension would make cross-dimension `Transition`s mismatch on `D`.

## Spatial primitives

### `Cell`
Final, immutable value type. The atomic spatial unit.
```java
public final class Cell {
  private final int x, y, z;
  public Cell(int x, int y, int z);
  public int x(); public int y(); public int z();
  public Cell plus(int dx, int dy, int dz);
  public double distance(Cell other);          // euclidean
  public double distanceSquared(Cell other);
  public int manhattan(Cell other);
  // value-based equals/hashCode (x,y,z)
}
```
No domain. Cells are compared only within a known domain context.

### `Position`
Final, immutable `(Cell, Domain)`.
```java
public final class Position<D extends Domain> {
  public Position(Cell cell, D domain);
  public Cell cell(); public D domain();
  // value-based equals/hashCode
}
```

### `Domain`
Used directly as a first-class object — there is no `DomainRegistry` and no integer id (removed as
clunky). Implementations **must** provide stable value-based `equals`/`hashCode` (Minecraft delegates
to the world's `NamespacedKey`, e.g. `minecraft:overworld`), since a `Domain` flows into `Position`
equality and is used as a map key.
```java
public interface Domain {
  int minY();                    // inclusive world floor
  int maxY();                    // inclusive world ceiling (overworld ≈ -64..320)
  boolean contains(Cell cell);   // within [minY, maxY]
  // MUST implement value-based equals/hashCode (identity of the underlying world)
}
```

### `DomainRegion`
A region of cells within one domain — the unifying "target/entry area" abstraction. A single block,
a 2×3 nether-portal plane, and a whole town are all `DomainRegion`s. (Replaces the old
`DomainDestination`.) It exposes **geometry only**; the cost estimate lives in the pluggable A*
heuristic (`03`), not here.
```java
public interface DomainRegion<D extends Domain> {
  D domain();
  boolean contains(Cell cell);
  /** Closest cell of this region to {@code from} (vector-algebra nearest entry for prisms);
   *  returns {@code from} if already inside. The heuristic picks its own metric over this. */
  Cell nearestBoundaryCell(Cell from);
  // additional geometry accessors (center(), averageY(), …) added as heuristics need them
}

// concrete DomainRegion impls (CellRegion, BoxRegion) live in `core`, not `core-api`
public final class CellRegion<D extends Domain> implements DomainRegion<D> { /* a single-cell region */ }
public final class BoxRegion<D extends Domain> implements DomainRegion<D> { /* an axis-aligned prism */ }
```
> **Placement:** `core-api` holds only the region/destination *interfaces* (`DomainRegion`,
> `Destination`). Their concrete value types (`CellRegion`, `BoxRegion`, `SingleDestination`) live in
> `core`, so embedders build them from `core` while the pure contract stays dependency-free.
Note on metrics: exposing the nearest cell (rather than a baked-in distance) lets an admissible
heuristic use euclidean/octile distance while a cheap/fast heuristic may use manhattan — manhattan
alone would overestimate when diagonal movement is allowed, so we keep the choice in the heuristic.

## Agent
Marker for the navigating entity — minimal at core level so `Mode`s can be typed against a concrete
agent downstream without casting. Nothing Minecraft-specific.
```java
public interface Agent {
  // empty at core-api; capability accessors are added by subtypes (MinecraftAgent, OdysseyPlayer)
}
```

## Step typing & instructions

### `StepType`
Any enum may serve (constraint `T extends Enum<T>`). It tags every `Step` in the result — both
`Movement`s (from `Mode`s) and `Transition`s declare one — so consumers can interpret and render
each step (e.g. pick a particle). `core-test` defines a trivial one; `minecraft-api` defines
`MinecraftStepType` (`WALK`, `FLY`, … plus action types like `COMMAND`, `MOUNT_HORSE`, `PLACE_BOAT`).

### `Instruction` (`I`)
`core-api` does not define an instruction type — `I` is an unbounded caller-supplied generic. It is
the optional payload for a step that requires the player to *act* (chiefly a command string).
`minecraft` defines the concrete sealed set (`04`). `Void` is used where instructions are unused.

### `TraversalState`
Immutable, sparse, hashable **typed key→value map** of accumulated agent condition. `DEFAULT` is the
empty map (the common case — no vehicle). Modes define their own keys; the map only ever holds the
overrides that differ from the base agent, so it stays tiny.
```java
public final class TraversalState {
  public static final TraversalState DEFAULT;                  // empty, interned
  public <V> V get(TraversalKey<V> key);                       // null if absent
  public <V> TraversalState with(TraversalKey<V> key, V value);// returns a new state
  public TraversalState without(TraversalKey<?> key);
  // value-based equals/hashCode over the underlying map
}

public final class TraversalKey<V> {   // typed key ⇒ cast-free get/with
  public TraversalKey(String name);
}
```
`TraversalState` never appears on the result `Step` — it is purely internal to the search. Design
note (`03`): the A* visited-set is keyed on **`(cell, TraversalState)`** because states are
*incomparable* (a boat is faster on water; on-foot is needed on land). When state is `DEFAULT`
throughout (the usual case) this degenerates to keying on `cell` alone.

## Modes & movement

### `Movement`
Output unit of a step: a reachable neighbor and how we got there.
```java
public final class Movement<T extends Enum<T>, I> {
  public Cell cell();                 // destination cell (same domain as input)
  public double cost();               // seconds
  public T stepType();                // usually the mode's primary type; may differ (e.g. boat entry)
  public TraversalState state();      // resulting state after the step
  public /* @Nullable */ I instruction();  // null unless this step needs a player action
  // value-based equals/hashCode on (cell, stepType, state)
}
```

### `Mode`
```java
public interface Mode<A extends Agent, T extends Enum<T>, I, D extends Domain> {
  T stepType();   // the mode's primary step type (used for `-no-mode` exclusion + default tagging)
  /**
   * From {@code from} in {@code domain}, using {@code agent} + {@code state}, produce all cells this
   * mode can reach in one step, their costs, resulting states, and any instruction. Block lookups go
   * through the ChunkProvider (Minecraft) and surface as FutureOr, so this returns a FutureOr of the
   * movement set. Pure/test modes with no IO return an immediate value.
   */
  FutureOr<Collection<Movement<T, I>>> step(A agent, Cell from, D domain, TraversalState state);
}
```
Ability/permission gating happens when the mode **list** is assembled (e.g. `FlyMode` only when
`agent.canFly()`), never inside `step`.

## Transitions

### `Transition`
One-directional single-step jump (renamed from "Tunnel"). Its **origin is a `DomainRegion`** (e.g. a
2×3 portal plane), its **destination is a `Position`** (you arrive at a point), and it may cross
domains and/or transform `TraversalState`. Carries a `StepType` and optional `Instruction`.
```java
public interface Transition<T extends Enum<T>, I, D extends Domain> {
  DomainRegion<D> origin();                // entry area you must reach to use it
  Position<D> destination();               // where you arrive (may be a different D instance / world)
  double cost();                           // seconds
  T stepType();                            // e.g. PORTAL, COMMAND, MOUNT_HORSE
  /** @Nullable — e.g. CommandInstruction("/home"); null for a walk-through portal. */
  I instruction();
  /** Transform state on traversal (identity for a plain teleport; sets VEHICLE=HORSE for a mount). */
  default TraversalState apply(TraversalState in) { return in; }
}
```
(Origin and destination are the same domain *type* `D` but usually different *instances* — a nether
portal goes from one `OdysseyWorld` to another.)
(No `isPseudo`: the origin/destination bookends of a search are an internal `core` concern — the
algorithm recognizes its own synthetic transitions by reference and never leaks a flag to consumers.
`apply` is also used in **Tier 1** to make optimistic estimates state-aware — e.g. after a mount, the
outbound estimates use horse speed; see `03`.)

`TransitionProvider` (the lazy, per-agent async supplier) is **not** in `core-api` — it belongs in
`minecraft-api` (`04`), since the generic `navigate` takes a pre-assembled `List<Transition>`.

## Destinations
```java
public interface Destination<D extends Domain> {
  Collection<DomainRegion<D>> regions();   // one logical destination; may span domain instances / endpoints
}
```
A `SingleDestination<D>` (in `core`) wraps one `DomainRegion<D>` (or a `CellRegion<D>`) for the
common case. The Tier-1 builder groups `regions()` by domain instance itself (no `byDomain()` map
needed).

## Result

### `Step` & `Path` — parameterized by *step type*, not domain
The end-to-end result is **flattened** into a single `Path` — an ordered list of `Step`s. A
`Transition` appears as a `Step` whose `stepType` is a transition type (and which may carry an
`instruction`); a domain change between consecutive steps marks where you crossed one.

`Step` is generic in the **position type `P`** rather than in a domain: it holds one `P position`
instead of a `(Cell, D domain)` pair. Core binds `P = Position<D>` (so `step.position().cell()` /
`.domain()` recover the old accessors); a platform façade can re-bind `P` to its own located type
(e.g. `org.bukkit.Location`) and hand back the very same `Path`/`SearchHandle` types. Because of
this, the *result containers* (`Path`, `NavigationResult`, `SearchHandle`) are generic in a **single
whole-step type `S`**, not in `(T, I, D)` — the step's own generics live inside `S`.
```java
public record Step<P, T extends Enum<T>, I>(
    P position,               // Position<D> in core; a native located type in a façade
    double cumulativeCost,    // seconds from origin
    T stepType,
    /* @Nullable */ I instruction) {}

public interface Path<S> {
  List<S> steps();            // ordered origin → destination (S is a Step<…>)
  double cost();
}
```
(`Path` no longer exposes `first()`/`last()`; callers read `steps()`. The concrete `PathImpl` lives
in `core`.)

### `NavigationResult` (sealed)
```java
public sealed interface NavigationResult<S>
    permits NavigationResult.Success, NavigationResult.Failure {
  boolean success();
  record Success<S>(Path<S> path) implements NavigationResult<S> { /* success() → true  */ }
  record Failure<S>(FailureReason reason) implements NavigationResult<S> { /* success() → false */ }
}

public enum FailureReason { NO_ROUTE, DESTINATION_UNREACHABLE, LIMIT_EXCEEDED, CANCELLED, TIMED_OUT, ERROR }
```

### `SearchHandle`
What `navigate` returns — the future plus cancellation, together.
```java
public interface SearchHandle<S> {
  CompletableFuture<NavigationResult<S>> future();
  void cancel();   // e.g. player logs off; completes the future with FailureReason.CANCELLED
}
```
A façade that re-locates steps (Position → Location) returns a `SearchHandle<Step<Location, …>>`
that wraps the core `SearchHandle<Step<Position<D>, …>>` and maps each step as the future completes.

## The search entry point

### `Scheduler`
```java
public interface Scheduler {
  void runAsync(Runnable task);                       // worker thread
  void runAsyncLater(Runnable task, long delayMillis);
  ExecutorService asyncExecutor();                    // for CompletableFuture composition
}
```
(Extended with location-aware scheduling in `04`.)

### `SearchSettings`
```java
public final class SearchSettings {
  int maxCellsVisited;            // default 10_000
  long maxWallClockMillis;        // default 60_000
  double tier1RecalcThreshold;    // default 1.30
  int runningAverageWidth;        // default 5..10 (fast heuristic)
  HeuristicStrategy heuristic;    // pluggable; default = admissible min-cost (03)
  // …
}
```

### `OdysseyApi`
```java
public interface OdysseyApi {
  static OdysseyApi load();   // ServiceLoader; core registers OdysseyApiImpl in META-INF/services

  <A extends Agent, T extends Enum<T>, I, D extends Domain> SearchHandle<Step<Position<D>, T, I>> navigate(
      Scheduler scheduler,           // the search runs here; passed in (impl is stateless)
      A agent,
      Position<D> origin,
      Destination<D> destination,
      List<? extends Mode<A, T, I, D>> modes,
      List<? extends Transition<T, I, D>> transitions,
      HeuristicStrategy heuristic,   // pluggable; also passed in per search
      SearchSettings settings);
}
```
The type parameters are verbose here, but downstream façades bind them all to concrete types
(`OdysseyPlayer`, `MinecraftStepType`, `MinecraftInstruction`, `MinecraftWorld`) so end users of
`navigatePlayer(...)` never see a generic. The result step type is `Step<Position<D>, T, I>`.
`Movement<T, I>` is the one flow type with no `D` — it carries no domain (the domain is implied by
the `step` call), so `core` stamps the domain onto each `Step`'s `Position` as it builds it from a
`Movement`.

`OdysseyApiImpl` is **stateless**: the `Scheduler` and `HeuristicStrategy` are per-search arguments
rather than construction state, so a single loaded instance serves every caller. `navigate` returns
immediately with a `SearchHandle`; the `Search` (see `03`) runs on the given `Scheduler`, and
cancellation is via `handle.cancel()`.

## `FutureOr` (sealed)
```java
public sealed interface FutureOr<T> permits FutureOr.Immediate, FutureOr.Pending {
  static <T> FutureOr<T> of(T value);                       // immediate (cache hit)
  static <T> FutureOr<T> ofFuture(CompletableFuture<T> f);  // pending (cache miss)
  boolean isImmediate();
  T value();                          // valid iff isImmediate()
  CompletableFuture<T> future();      // valid iff !isImmediate()
  <R> FutureOr<R> map(Function<T, R> fn);
  void whenReady(Consumer<T> cb, Executor exec);            // immediate → run now; pending → attach

  record Immediate<T>(T value) implements FutureOr<T> { /* … */ }
  record Pending<T>(CompletableFuture<T> future) implements FutureOr<T> { /* … */ }
}
```
`FutureOr` is the linchpin of cooperative scheduling: the search consumes `Immediate` results in a
tight synchronous loop and only registers a continuation (parking its worker) on a `Pending`.

## Logging
Core depends on no logging framework; a minimal SLF4J-like interface is injected:
```java
public interface OdysseyLogger {
  void trace(String msg, Object... args);
  void debug(String msg, Object... args);
  void info(String msg, Object... args);
  void warn(String msg, Object... args);
  void error(String msg, Throwable t, Object... args);
}
```
Algorithms log heavily at `trace` (candidate pops, parks, recalcs). Logger messages are **not**
internationalized (user-facing i18n is in `06`).
