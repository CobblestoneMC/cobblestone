# Odyssey — `core-api`

The pure, Minecraft-agnostic contract. No third-party dependencies. Package
`net.whimxiqal.odyssey.api`. Everything here is designed so `core` can implement the algorithms
and so any embedder (Minecraft or otherwise) can drive navigation over abstract space.

> Stubs below show intent, not final formatting. `double` is used for all costs (seconds).
> Generics: `A extends Agent` (agent type), `T extends Enum<T>` (mode-type enum). Modes are
> `Mode<A, T>`. Types that flow through a whole search (`Search`, `Path`, `NavigationResult`)
> are generic on `A` and `T` so no downcasting is ever needed downstream.

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
Note: no domain. Cells are compared only within a known domain context.

### `Position`
Final, immutable `(Cell, domainId)`.
```java
public final class Position {
  private final Cell cell; private final int domainId;
  public Position(Cell cell, int domainId);
  public Cell cell(); public int domainId();
  // value-based equals/hashCode
}
```

### `Domain`
```java
public interface Domain {
  int id();                 // internal integer id (from DomainRegistry)
  int minY();               // inclusive world floor
  int maxY();               // inclusive world ceiling (overworld ≈ -64..320)
  boolean contains(Cell cell);   // within [minY, maxY]
}
```

### `DomainRegistry`
Bidirectional `String key ↔ int id` map. **Instance-scoped**, owned by `OdysseyApi` (not static).
Thread-safe (backed by concurrent maps + an `AtomicInteger` counter).
```java
public interface DomainRegistry {
  int idFor(String key);          // assigns a new id if unseen
  String keyFor(int id);
  boolean isRegistered(String key);
  Domain domain(int id);          // resolves domain metadata (minY/maxY) registered alongside
  void register(String key, int minY, int maxY);
}
```

## Agent
Marker for the navigating entity. Deliberately minimal at core level — it exists so `Mode`s can be
typed against a concrete agent downstream without casting. It carries nothing Minecraft-specific.
```java
public interface Agent {
  // intentionally empty at core-api; capability accessors are added by subtypes
  // (MinecraftAgent adds canBreak(Cell), OdysseyPlayer adds hasPermission/canFly, etc.)
}
```

## Modes & movement

### `ModeType`
Just a constraint: any enum may serve. `core-test` defines a trivial one; `minecraft-api` defines
`MinecraftModeType`.

### `Movement`
Output unit of a step: a reachable neighbor and how we got there.
```java
public final class Movement<T extends Enum<T>> {
  private final Cell cell;              // destination cell (same domain as input)
  private final double cost;            // seconds to perform this step
  private final T modeType;             // which mode produced it
  private final TraversalState state;   // resulting state after the step
  // accessors; value-based equals/hashCode on (cell, modeType, state)
}
```

### `TraversalState`
Small, immutable, hashable bundle of accumulated mutable condition. Empty/`DEFAULT` for the common
case (no vehicle), so it collapses to a singleton and adds no overhead. Extensible by downstream
modules via a typed key/value or a sealed subtype set; core defines the contract + a `DEFAULT`.
```java
public interface TraversalState {
  // implementations must provide value-based equals/hashCode
  TraversalState DEFAULT = /* canonical empty singleton */;
}
```
Design note (see `03-core-algorithm.md`): the A* visited-set is keyed on **`(cell, state)`** because
states are *incomparable* (a boat is faster on water; on-foot is needed on land), so a cheaper entry
in one state does not dominate a costlier entry in another. When `state == DEFAULT` throughout (the
usual case) this degenerates to keying on `cell` alone.

### `Mode`
```java
public interface Mode<A extends Agent, T extends Enum<T>> {
  T type();
  /**
   * From {@code from} in {@code domain}, using {@code agent} for context, produce all cells this
   * mode can reach in one step and their costs. May require block lookups; those go through the
   * ChunkProvider (Minecraft) and are surfaced as FutureOr, so this returns a FutureOr of the
   * movement set. In pure/test modes with no IO it returns an immediate value.
   */
  FutureOr<Collection<Movement<T>>> step(A agent, Cell from, Domain domain, TraversalState state);
}
```
Ability/permission gating happens when the mode **list** is assembled (e.g. `FlyMode` is only added
when `agent.canFly()`), never inside `step`.

## Tunnels

### `Tunnel`
One-directional single-step traversal that may cross domains **and/or** transform state.
```java
public interface Tunnel {
  Position origin();                       // where you must be to enter
  Position destination();                  // where you arrive
  double cost();                           // seconds
  /** Transform state on traversal (identity for plain teleports; sets vehicle=HORSE for a mount). */
  default TraversalState apply(TraversalState in) { return in; }
  /** True for the synthetic origin/destination pseudo-tunnels (no real entry/exit block). */
  default boolean isPseudo() { return false; }
}
```
Two synthetic tunnels bookend every search: an **origin pseudo-tunnel** at the player's current
`Position` (no origin, only a destination) and a **destination pseudo-tunnel** at each
`DomainDestination` (no destination, only an origin). See `03`.

### `TunnelProvider`
Lazy, async supplier of the tunnels an agent may use.
```java
@FunctionalInterface
public interface TunnelProvider<A extends Agent> {
  CompletableFuture<List<? extends Tunnel>> compute(A agent);
}
```

## Destinations

### `DomainDestination`
Single-domain target: a completion predicate + an **admissible** approximate-cost heuristic
(a lower bound on the true remaining cost, so Tier-2 A* stays well-behaved with the default heuristic).
```java
public interface DomainDestination {
  int domainId();
  boolean isSatisfiedBy(Cell cell);
  double approximateCost(Cell from);   // admissible lower bound (e.g. euclid × min per-cell cost)
}
```
The common case is a single cell:
```java
public final class CellDestination implements DomainDestination { /* exact-cell match */ }
```

### `Destination`
```java
public interface Destination {
  Map<Integer, DomainDestination> byDomain();   // domainId → per-domain destination
  default Collection<DomainDestination> all() { return byDomain().values(); }
}
```
A wrapper `SingleDestination` covers the overwhelmingly common one-domain case.

## Paths & results

### `Path`
Solved, single-domain series of steps.
```java
public interface Path<T extends Enum<T>> {
  int domainId();
  List<Step<T>> steps();      // ordered; first step is the entry cell, last is the exit cell
  double cost();
  Cell origin(); Cell destination();
}

public final class Step<T extends Enum<T>> {
  Cell cell(); double cumulativeCost(); T modeType(); TraversalState state();
}
```

### `PathString`
End-to-end result: alternating `Tunnel` (node) / `Path` (edge), starting and ending with a `Path`
(the bookend pseudo-tunnels are the conceptual first/last nodes but carry no geometry). Provides a
typed iterator so consumers (navigators) can walk it without casting.
```java
public interface PathString<T extends Enum<T>> {
  double cost();
  PathStringIterator<T> iterator();
}

public interface PathStringIterator<T extends Enum<T>> {
  boolean hasNext();               // another (tunnel, path) hop?
  Hop<T> next();                   // the tunnel to traverse, then the path that follows it
  Path<T> firstPath();             // the leading edge before any tunnel
}

public final class Hop<T extends Enum<T>> { Tunnel tunnel(); Path<T> path(); }
```

### `NavigationResult`
```java
public interface NavigationResult<T extends Enum<T>> {
  boolean success();
  Optional<PathString<T>> pathString();
  FailureReason failureReason();     // enum: NO_ROUTE, DESTINATION_UNREACHABLE, LIMIT_EXCEEDED,
                                     //       CANCELLED, TIMED_OUT, ERROR
}
```

## The search entry point

### `Scheduler`
Injected into `core` so it can run/park work without knowing the platform. (Full contract lives in
`04-minecraft-model.md`; `core-api` declares the minimum it needs.)
```java
public interface Scheduler {
  void runAsync(Runnable task);            // worker thread
  void runAsyncLater(Runnable task, long delayMillis);
  ExecutorService asyncExecutor();         // for CompletableFuture composition
}
```

### `SearchSettings`
All tunable limits/knobs a search needs, populated from config downstream.
```java
public final class SearchSettings {
  int maxCellsVisited;            // default 10_000
  long maxWallClockMillis;        // default 60_000
  double tier1RecalcThreshold;    // default 1.30
  int runningAverageWidth;        // default 5..10 (heuristic strategy)
  HeuristicStrategy heuristic;    // pluggable; default = admissible min-cost
  // …
}
```

### `OdysseyApi`
The generic service. Owns the `DomainRegistry` and `Scheduler`.
```java
public interface OdysseyApi {
  DomainRegistry domains();

  <A extends Agent, T extends Enum<T>> CompletableFuture<NavigationResult<T>> navigate(
      A agent,
      Position origin,
      Destination destination,
      List<? extends Mode<A, T>> modes,
      List<? extends Tunnel> tunnels,
      SearchSettings settings);

  /** Cancel an in-flight search (e.g. player logs off). */
  void cancel(SearchHandle handle);
}
```
`navigate` returns immediately with a future; the `Search` (see `03`) runs on the `Scheduler`. A
`SearchHandle` is also exposed (via an overload or the future's wrapper) so callers can cancel.

## `FutureOr`
```java
public final class FutureOr<T> {
  public static <T> FutureOr<T> of(T value);                       // immediate (cache hit)
  public static <T> FutureOr<T> ofFuture(CompletableFuture<T> f);  // pending (cache miss)
  public boolean isImmediate();
  public T value();                          // valid iff isImmediate()
  public CompletableFuture<T> future();      // valid iff !isImmediate()
  public <R> FutureOr<R> map(Function<T,R> fn);
  public void whenReady(Consumer<T> cb, Executor exec);  // immediate → run now; pending → attach
}
```
`FutureOr` is the linchpin of cooperative scheduling: the search consumes `isImmediate()` results in
a tight synchronous loop and only registers a continuation (parking its worker) when it hits a
pending one.

## Logging
Core must not depend on any logging framework. A minimal SLF4J-like interface is injected:
```java
public interface OdysseyLogger {
  void trace(String msg, Object... args);
  void debug(String msg, Object... args);
  void info(String msg, Object... args);
  void warn(String msg, Object... args);
  void error(String msg, Throwable t, Object... args);
}
```
Algorithms log heavily at `trace` (candidate pops, parks, recalcs) so unit tests and live servers
can diagnose behavior. Logger messages are **not** internationalized (see `06` for user-facing i18n).
