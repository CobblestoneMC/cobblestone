# Changes to design
These are changes I made to the overall structure/design since initial implementation

- Restructured projects into subfolders for better organization
- Got rid of the "DomainRegistry" -- instead, we will have Domain objects that we use directly. This gets rid of "domain ID" concepts. In practice, these should be implemented with hashCode and equals just pointing the internal ID used to identify the domain, which for Minecraft is the NamespacedKey (like `minecraft:overworld`)

## Result-type generics reshaped (Phase 5 refactor) — `02`, `glossary`
- `Step` is now `Step<P, T extends Enum<T>, I>` — a **record** holding one `P position` instead of a
  `(Cell, D domain)` pair. Core binds `P = Position<D>`; a platform façade can re-bind `P` to a
  native located type (e.g. `org.bukkit.Location`).
- The result *containers* collapsed from `<T, I, D>` to a **single whole-step type** `S`:
  `Path<S>`, `NavigationResult<S>` (now also has `success()`), `SearchHandle<S>`. `Path` dropped
  `first()`/`last()` (use `steps()`).
- `OdysseyApi.navigate(...)` now takes the `Scheduler` and `HeuristicStrategy` as **arguments**
  (impl `OdysseyApiImpl` is stateless) and returns `SearchHandle<Step<Position<D>, T, I>>`. `core`
  registers `OdysseyApiImpl` via `META-INF/services` so `OdysseyApi.load()` works.
- Concrete region/destination value types (`CellRegion`, `BoxRegion`, `SingleDestination`) moved from
  `core-api` to `core`; `core-api` keeps only the `DomainRegion`/`Destination` interfaces.

## Native platform façade + platform/plugin split — `05`, `06`, `07`, `08`, `01`
- The platform façade is one generic `PlatformOdysseyApi<P, L>` (in `minecraft-api`, `P`/`L`
  unbound so no Bukkit/Sponge dep), bound per platform (`PaperOdysseyApi extends …<Player,Location>`).
  Inputs and results are fully native — even the result `Step` is located by `L` (a
  `Position→Location` adapter maps the handle as it completes). Developer transitions are native too
  (`PlatformSingleCellTransition<L>` / `…Provider<P,L>`).
- The platform impl (`PaperOdysseyApiImpl`) is a **library you instantiate**
  (`new PaperOdysseyApiImpl(plugin, TransitionRegistry)`), **not** a registered service. The
  transition `TransitionRegistry` is **owned by the plugin layer** and injected in; the API only
  registers into / reads from it.
- New **plugin-layer API object** `PlatformOdysseyPluginApi<P, L>` (in `minecraft-plugin-api`),
  bound per platform in a new published `paper-plugin-api` module (`PaperOdysseyPluginApi`). It
  carries the opinionated surface (`registerDestinationProvider`, `registerNavigatorFactory`) plus a
  `platform()` accessor. Odyssey's plugin registers **one** service — the plugin API — and callers
  reach navigation through `.platform()`. (`registerTransitionProvider` stays on the platform API.)
- Publishing: `paper-core` is published (shade the platform lib into your own plugin);
  `paper-plugin-api` is published (extend the running plugin); `minecraft-plugin-api` becomes a
  published-but-internal dep for closure.

## Phase 6 split into three sub-phases — `11`
- **6a Foundation** (API layering + Paper plugin bootstrap + config + i18n),
  **6b State** (data layer + waypoints + destinations),
  **6c Experience** (navigators + trips + portal discovery + command trees + metrics).

## Phase 6a implemented — `06`, `07`, `01`
- **Plugin API is fully native-typed and extends the platform API.**
  `PlatformOdysseyPluginApi<P,L> extends PlatformOdysseyApi<P,L>` — the `platform()` accessor was
  **removed**; the plugin API *is* the navigation API plus `registerDestinationProvider`/
  `registerNavigatorFactory`. The whole developer surface is generic over native `P`/`L`:
  `DestinationProvider<P>`, `NavigatorFactory<P,L>`, `NavigatorContext<P>`, `Navigator<L>`, and
  `MinecraftPath<L> extends Path<Step<L,…>>` (native `L` in the step slot, not `Position<D>`).
  `MinecraftDestination` stays core-typed (`Destination<MinecraftWorld>` — a multi-`DomainRegion`
  goal that cannot collapse to one native `L`). `PaperOdysseyPluginApi extends PaperOdysseyApi,
  PlatformOdysseyPluginApi<Player,Location>`.
- **Impl by delegation:** `OdysseyPluginApiImpl<P,L>` (in `minecraft-plugin`) composes a
  `PlatformOdysseyApi<P,L>` and forwards navigation; `PaperOdysseyPluginApiImpl` extends it and passes
  the `PaperOdysseyApiImpl`. Registered as the single `PaperOdysseyPluginApi` service.
- **New module** `paper-plugin-api` (published), package `net.whimxiqal.odyssey.paper.plugin.api`.
  `minecraft-plugin-api` is now published (internal-but-required for closure).
- **Packaging:** `paper-plugin` uses **GradleUp shadow** to bundle only our `net.whimxiqal.odyssey.*`
  modules (they are not on a public Maven repo); third-party runtime libs are declared in the
  `paper-plugin.yml` **loader** (`PaperOdysseyLoader` → `MavenLibraryResolver`, SnakeYAML for now) and
  downloaded by Paper at runtime. Adventure + paper-api stay provided. `shadowJar` is wired into
  `assemble`, so a plain `./gradlew build` emits the shippable jar. Modern `paper-plugin.yml`
  (`loader`, no `bootstrapper`, Brigadier commands — no `commands:` block).
- **Config:** platform-neutral `ConfigManager` + typed `ConfigKey`/`Codec` on SnakeYAML; documented
  `config.yml`; mutable keys update on reload, immutable changes WARN and are reported to the admin.
- **i18n:** `Messages` renders Adventure `Component`s from `messages.properties` with one-indexed
  `{N}` placeholders (params in the secondary color); typed `Message0..3` enforce arity. Palette
  (`OdysseyColors`): primary `#4AA8FF`, secondary `#FFC857`, info gray, success green, error red;
  `[✦]` prefix toggled by `messages.show_prefix`. Prefix/send helpers live in `minecraft-plugin` so
  Sponge reuses them. 