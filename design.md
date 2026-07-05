# Odyssey
Odyssey is a Minecraft plugin for Java Edition that allows players to navigate around the server more easily. It exists today as a version that I developed off-and-on for 5 years and I want to rewrite it as Odyssey 2.0 from the ground up using the knowledge I've since gained and using AI to write the initial implementation and much of the starting boilerplate.

The general idea is that a user will request to navigate from an origin to a destination. The response will be a future to a calculation that runs asynchronously by requesting information about the player who whomever is navigating and about the world to give a navigable path back to the user. Finally, the plugin will display this path to the user in some visual nature, like particles floating above the ground through which the player may walk.

Importantly, the core of the project should be abstracted away from Minecraft internals, so I will be defining abstraction layers through which we can separate the underlying algorithms to perform the pathfinding and the implementations that put it in Minecraft terms and ultimately send information to the user in-game.

There are multiple Java Edition plugin platforms, such as Paper/Folia, Sponge, and Fabric. Odyssey will be implemented on all of them. We will start with Paper/Folia, but the subproject structure will abstracted such that more implementations relatively easy to plug in. We will not support Bukkit or Spigot.

## Minecraft
Minecraft is a game in which a player moves through one or multiple worlds made up of large 1-cubic-meter blocks. Practically every block is breakable, making the world a very dynamic and editable landscape. It is mostly a sandbox world where the player may go anywhere and do anything. There are multiple worlds, such as the Overworld, where players spend most of their time, the Nether, which is a hellish and dangerous area that has valuable and necessary resources, and the End, where the player technically finishes the game but also has more valuable resources. The game is also heavily modded by the community so all sorts of other ways to move around are possible such as teleporting to waypoints or other players or flying on command.

There are many ways to get around in Minecraft, like walking, swimming, boating, riding horses, taking rail systems via minecart, flying with wings called Elytra, flying just regularly if they are in "Creative" mode, etc. When playing the game in regular "Survival" mode, sometimes getting from A to B is just what a player wants to do but navigating there means traversing thousands of blocks and knowing the fastest way to get there is hard. Especially when they are deep underground in mineshafts. This is where Odyssey shines -- giving players a fast and convenient path to follow by executing requests to navigate to certain destinations. Personally, I would much prefer this on my personal servers than adding ways for players to "break" the game by allowing teleportation.

## Language
The plugin will be written solely in Java and use Kotlin-based Gradle. Dependencies to outside sources should be at a minimum, and the targeted Java API JDK version is Java JDK 21. The domain is "net.whimxiqal.odyssey". Use the MIT license.

# Project Structure
The project structure should be comprised of the following subprojects:
- `core-api`: this contains all basic structures needed for general-purpose navigation, completely unrelated to Minecraft specifically. This will also be the dependency for any projects that want to make generic requests for Odyssey, whether that's another Minecraft plugin developer or someone else that at runtime will have a Odyssey implementation
- `core`: the basic "Odyssey" implementation of the api. This is where all the algorithmic logic will be to navigate on the abstract types that we have defined in the api library and implements some of the navigation request methods that exist in the api. This is also the only thing that's technically needed during runtime to do Odyssey navigation on abstract types.
  - depends on: `core-api`
- `minecraft-api`: very basic API library that contains Minecraft-specific types and functions
- `minecraft`: A subproject that defines generic Minecraft-specific implementations. Most importantly, it defines Minecraft-specific modes of transportation, like walking and swimming, without talking to a specific Minecraft plugin platform API. So this is where we may have a "OdysseyBlock" which will be implemented in the Paper version of Minecraft by creating a "PaperOdysseyBlock" that really contains a "Paper" Block object, etc.
  - depends on: `core`
- `platform-apis`: Platform-specific APIs. This will shadow some of the API navigation options that we have in the `core-api`, but use platform-specific terminology. For example, "navigate a Player to a Block" as opposed to "navigate an Agent to a Cell". These will be made available on Maven for developers to interact with Odyssey assuming a Odyssey runtime is available on the server.
  - `folia-api`: ...
  - `sponge-api`: ...
  - `fabric-api`: ...
  - each depend on: `minecraft`
- `platforms`: Platform-specific implementations. This is where we will implement any final abstractions needed to hook our basic algorithm into the Minecraft ecosystem. These are not full plugins, just implementations to the core library but these will be made available on Maven in case developers want to use the platform-specific implementation to build their own plugin wrapper.
  - `folia`: ...
  - `sponge`: ...
  - `fabric`: ...
  - each depend on: their respective API subproject
- `minecraft-plugin-api`: A subproject to enable developers to connect their minecraft plugins to the Odyssey plugin in minecraft-specific ways that the Odyssey pluginw would care about. For example, registering specific destinations. Also provides a "Navigator" API, which developers may implement as a way to choose how to display the results of a navigation to a player. Usually it would just be a path of particles, but we can also add guide-animals (see Citizens integration) or sound-based navigation by following a repetitive sound!
  - depends on: `core-api`
- `minecraft-plugin`: A subproject to hold information needed for any of the minecraft plugin implementations. We will put shared helper functions here to implement command behavior needed for the commands that will be written into each of the plugin implementations.
  - depends on: `minecraft`
- `plugins` (folder): Platform-specific plugins. These use their respective platform-specific implementations to finally construct plugins that 
  - `folia-plugin`: ...
  - `sponge-plugin`: ...
  - each depend on: `minecraft-plugin` and their respective platform-specific implementation subproject
  - each will define its own commands using the native command API within the platform API. Using a cross-platform system like Cloud or Aikar introduces an additional dependency and if we put shared behavior in the `minecraft-plugin` library whenever possible, then each command tree definition will be the roughly the same for each platform implementation with the platform's syntactic flavor but the logic will still be handled in the shared library.
- Platform-specific and plugin-specific integrations: Other plugins provide useful information to Odyssey that are relevant for server owners that use both Odyssey and the other plugin on their server. For example, the Essentials Paper plugin adds teleportation functionality to go "home" or go to "spawn" which are locations that are immediately accessible to players at all times. The integration plugins allow server owners to enable this interaction so Odyssey knows that this teleportation is possible.
  - `essentials`: Registers teleportation commands to `core-api`, registers player's home as a waypoint destination in `minecraft-plugin-api`, etc...
  - `citizens`: Creates commands to "guide"

- `core-test`: An add-on to the `core` project that contains infrastructure to verify the results of the algorithms. Here we will define a small engine that will enable us to create fake worlds that simulate what a bunch of Minecraft worlds may look like and test the resultant paths are exactly what we expect. The tests of this subproject will use the engine to pull in certain worlds and verify the results of various navigation requests.
  - depends on: `core`
- `playground`: A visualization tool. This will depend on the `core-test` library, along with `core` and obviously `core-api` and also depend on a 3D rendering library. Here, we will create allow camera movement to visualize in 3D a calculated navigation, and with additional controls, allow visualizating the progression of the algorithm itself.
  - depends on: `core-test`

Each of these subprojects should be managed by gradle in a well-defined subproject structure. The dependencies should be minimal, and only some of them will contain publishing configuration to Maven.

## Core API
In the Core API we will have our basic components:
- Cell: a final class of a single 1x1x1 3D unit of space. This is the atomic unit we will work with throughout our algorithms and is analogous to a single block in Minecraft. Defined simply by `x`, `y`, and `z` integer coordinates (no Domain).
- Domain: an interface of a contiguous grid of cells. This is analagous to a World in Minecraft, such as the Overworld and The Nether. Importantly, any cell is technically traversable to any other cell in the domain as long as there are no barriers. That is not to be said for cells in different domains. Think distinct mathematical vector spaces.
  - Domains should be ID-ed just by an integer. In Minecraft, we really ID our worlds by something called a NamespacedKey, which is a pair of "key" and "value" and the string version looks like "key:value". We should have a static thread-safe mapper that maps the key of the implementation (Minecraft) to our internal core library ID (integer) that we can use to convert back and forth when needed so we don't have to introduce "NamespacedKey" as a dependency at this level.
- Mode: an interface for a method of transportation. This is analagous to walking, swimming, jumping, etc. Modes define behavior for which nearby cells are navigable based on the current state of the agent's location and the agent itself throughout the navigation algorithm. Each mode has a "mode type" enum field, so Mode is generic on this enum. The return type is a future! More below.
- Navigation: a "search" session. This is created upon request, either from the developer API or from a player in game. The goal of the navigation is to find a Path that inflicts the least cost (which is usually a function primarily if not entirely consisting of the time required to traverse it). This has multiple generics: "Mode", "PathNode", perhaps others.
- Path: a series of cells and the mode types used to navigate an Agent during a Navigation within the same Domain.
- PathString: a series of Paths that are connected by Tunnels, which may be the glue that connects to paths from different domains.
- Tunnel: an interface for single-step traversal between any two domained cells. The two cells may be in different domains. Each Tunnel has a traversal cost associated with it.
- Destination: an interface for a destination of a Navigation. This doesn't have to be a single block -- in fact, a Destination only contains the condition that satisfies the Agent "reaching" the destination and contains an "approximate" cost calculation to know how close a certain block in the navigation algorithm is.
- OdysseyApi: A service that provides generic navigation functionality:
  - navigate(): Navigates an Agent from an origin Cell+Domain to a Destination using a provided series of Modes and a provided series of Tunnels accessible by the Agent.

## Core
### Core algorithm
The core library has the central algorithm. The multi-domain navigation algorithm is broken into two phases:
1. Graph-based multi-domain Dijkstra's algorithm, where "unsolved paths" are edges and "tunnels" are nodes
2. Domain-based single-domain A* algorithm, where we solve "unsolved paths" to try to create an entire solved path.

The intuition here is that Minecraft server navigation oftentimes require traversing multiple Worlds in modern servers, whether that's using a command to teleport, using special teleport pads to jump to another block in the same world, or using Nether highways (for every block you move in the Nether, you move 8 blocks in the overworld), so we have to use an algorithm that makes use of the possibility that the fastest (lowest-cost) path would be using these "wormholes" (tunnel) to other places on the server. But, since we don't know if any two tunnel endpoints in a given domain are actually traversable on a path, we start by crafting a graph of "unsolved" paths where the cost to traverse it is approximated. Once these approximate paths are constructed into the graph structure, we can solve it using Dijkstra's algorithm. Then, we take each of these unsolved paths and solve each of them using the 2nd tier A* algorithm. If it all succeeds, we can return the entire Path to the requester. If any of the paths don't succeed, then we stop searching and mark the "unsolved path" with infinite cost. Then we recalculate the Tier 1 graph path and test out a different series of paths, and then continue with Tier 2 etc. Either we get a valid Path, or we run out of options and return failure to the user.

#### Algorithm Tier 1: Graph/Dijkstra
As stated, a graph is created where Tunnels are the nodes and "unsolved" paths are the edges. When a Navigation begins, we build a graph. 

The graph should be an abstract class with generics for Node and Edge types and solves the fastest path using Dijstra's algorithm. The fastest-path algorithm should be unit-testable. The format of the fastest path results should be a special type of alternating list of Nodes and Edges, since the structure will always start with a Node, end with a Node, and have Edges and Nodes alternating in between. There should be a "traversal" function on this returned Path object. Perhaps the best way to traverse such a path would be to have something similar to an "Iterator" that has a "hasNextEdge", and if it returns true, we can call "next()" and get a pair object of Edge and Node. A path always has at least a single Node, so callers would always have a "currentNode()" option that the traversal could start with. Although, in practice, in Minecraft the starting "Node" is really an "Identity" tunnel because it's just where the player is standing currently, so there isn't an "origin" to the tunnel. Similarly, there isn't a "destination" to the actual destination "Node", so that also is not a Tunnel. But from the perspective of the Graph algorithm, we should consider the start and end Nodes so we should include it in the result.

In Navigation, we create such a Graph using the provided Tunnels as Nodes. Directional edges are needed, where each Tunnel's destination cell is connected via an edge to each other Tunnel's origin cell that has its origin cell in the same domain. These edges should be called "VirtualPath", which are the "unsolved" paths that we discussed earlier that have an approximate cost associated before we actually perform the in-domain A* search. The approximate cost equation will be optimistic. Notably, it would be naive to actually load all these edges into memory because in practice, we will only use a very small subset of these VirtualPaths since most paths are in fact traversable in Minecraft. We can make that assumption here. So instead, we should load the edges as we need them. So, the abstract Graph equation should expect Nodes that provide their own edges when requested. So, even though Tunnel and VirtualPath are really the functional Node and Edge of our Graph, we need wrapper types (GraphTunnel and GraphVirtualPath) that extract the information needed by the Graph. GraphTunnel will encapsulate a Tunnel and also a reference to the entire set of Tunnels memoized by the domains of the their origins and destinations so when the Graph requests a given Node's outbound Edges, GraphTunnel.outboundEdges() will produce GraphVirtualPaths that all connect to the different Tunnels that have origins of the same domain as the source Node's destination domain. Then the Graph algorithm doing Dijkstra's stores those edges in memory and continues with the traversal.

Remember that VirtualPaths are mutable, and the Graph is a long-lived object in the Navigation as we "solve" a series of VirtualPaths given to us by the solving of the fastest path in the Graph. If any VirtualPath fails, we must elegantly set the VirtualPath as "failed" and the "cost" function used to evaluate the "fastest path" in the Graph will evaluate to "untraversable".

#### Algorithm Tier 2: VirtualPath/A*
A virtual path uses A* to solve for a heuristically calculated "fastest" path throughout a single Domain. The virtual path is created by our Graph algorithm in Tier 1. We are given a series of Modes at our disposal, which are effictively the methods of transportation allowed by the Agent that is traversing. In practice, in Minecraft, this may be "walking" or "swimming".

Each mode implements an interface method `step` that takes as input a Cell (and Domain) and will output a series of Cells and the costs to reach those Cells. (The domain is required to remain the same so there's no domain outputed). The result may be named a generic "Movement" class. In testing, we will probably just have a simple one called "Move" that enables the algorithm to move in any direction including diagonally (which indeed is what we will do with "flying" or "walking" in Minecraft, where all diagonal cells are accessible if we calculate their are reachable via standard Minecraft movement).

I briefly considered a TraversalMetadata as an experimental idea I have to keep track of what changes a given traversing Agent may be going through. For example, in Minecraft, players may swim horizontally by "sprinting" in water, but only if they are fully submerged in Water. When horizontal, they can fit through 1x1 holes in walls. So, if there is a puddle of only 1 block tall water, they cannot enter "horizontal" swimming mode unless they get their head fully underwater. So there should be a "state" associated with each step that tells us what mutations the agent has gone through and then each mode can consider for itself whether the agent (which we would need an interface for to the nagivation algorithm) can perform certain movements given the current traversal state. But I think this is overly complicated and isn't really needed for most users. I think let's skip this for now.

The A* algorithm class will attempt all the modes available for a given algorithm step, accumulate all movements possible, for each Cell keep only the movement with the lowest cost, and continue with the A* algorithm using this Cell and cost. I won't dictate the A* algorithm here, but to summarize for the sake of completeness, we will have a "visited" set and a "candidate" priority queue, and we continue searching for our destination by taking the candidate with lowest overall approximate cost -- this includes the cost to get to this candidate plus the approximate cost from the candidate to the destination. Every step should have a reference to the previous step taken, so once we solve it, we can backtrack from the end to easily get our entire solved path.

I should note that "Destination" here is the generic Destination defined in the API -- it might not be a single Cell, it instead is a generic object that defines both a completion criteria and also an approximate cost calculation. Most usually, the implementer of a Destination will simply be a Cell, which is the case whenever we are calculating for a VirtualPath that leads to the origin of a Tunnel or to a final destination that can be described as a singular location. However, in the case when we are searching for a more complicated destination, like trying to simply traverse to a specific Domain by incurring the lowest cost, or perhaps in Minecraft trying to reach anywhere inside of a town, which would be defined as a wide region of space, it is not sufficient to talk of "destination" as a single location.

The Path should be made up of Steps, which each contain a Cell, the cost to reach it, and the Mode class used to get there. Since Mode will be a generic type on the entire Navigation class and the result type of the API, this Mode class can also be used to do certain things on the caller side like display a different type of Particle in minecraft depending on what action they should take to navigate through the blocks ahead.

Modes return a Future to provided blocks, so the entire A* search algorithm and the wrapping Tier 1 graph algorithm and indeed the entire Navigation are all asynchronous. This is because technically, we must realistically implement Modes by performing some amount of IO (round-trip to the server thread) that might take some time to respond. We will implement caching layers in Minecraft to ensure that in practice we don't wait too long, but technically the blocks that we operate on are not always readily available.

The approximation function for the A* algorithm is the most interesting part. In general, it should be a calculation of the cost to traverse a single Cell multiplied by the number of Cells to reach the destination. However, the cost to traverse a Cell is dependent on how the Mode is applied to any given location. For example, if a player can walk in Minecraft over dirt but is about to hit a wall, we might assume that we can walk the entire rest of the way and will multiply our low-cost walk Mode cost of the current Cell to the rest of the distance and get an overall low cost. Once we reach the wall, though, our Modes will be applied such that only `DIG` mode gives candidates which provides much higher cost (it costs more time and effort to break through blocks than simply walking through air), so suddenly our approximation jumps up drastically. If the wall is only one block thick, then this is silly because the approximation function would essentially be thinking the wall is infinitely thick.

To solve this problem, we can use the heuristic that generally modes of transportation are available generally in clusters. A lake would provide swimming, a field would provide walking, the solid ground provides digging (mining), and air provides flying. However, we want to be tolerant to outliers. So, we should use a running cost average when calculating future approximations. So, the cost to walk up to a wall is associated with the `WALK` mode, then the cost to dig one block through the wall is the cost associated with the `MINE` mode. If we have a heuristic running-average cost width of 5, then we may take the cost of the previous 4 walked steps in addition to the 1 mined step and average it out to be mostly like another walked step. Only once we dig our 2nd and 3rd blocks will the cost start to get really high and we will think "huh this wall is pretty thick I guess". In this way, we will not completely discount high-cost Mode output immediately. Let's start with a pretty modest running-average cost width like 5 or 10, but make sure it's configurable.

The distance equation can just be the euclidean distance.

#### Return type:
The return type of the algorithm is a Path. The path can produce an iterator that is the same type of iterator in the Graph description. In fact, we don't really need an Iterator in the Graph code, just here in the Path because we do not internally need to traverse the completed graph. The "Node" type produced by the PathIterator should be a the Tunnel generic type and the "Edge" type should be a Path. We needn't have Nodes on either end, so it should start with an Edge, end with an Edge, have Nodes in between.

### Other core library things
Logging -- we will have a generic logger interface that ultimately will be hooked into a Minecraft plugin downstream. But we want to ensure we have lots of logging (mostly at trace-level) so we get useful information when unit testing and even testing on a real Minecraft server.


## `minecraft` API subproject
- MinecraftModeType: Enum: `WALK`, `JUMP`, `SWIM`, `FLY`, `BOAT`, `MINE`, `RIDE_HORSE`, ...
- MinecraftMode: interface extending `Mode` that also exposes `MinecraftModeType type()`
- `OdysseyPlayer`: interface for simple things we care about to tell us what kind of Modes a player has access to.
- API class:
  - `navigate` using Minecraft mode types for generic fields. I don't expect this to be incredibly useful for general public use, but I think for project structure, this is the correct abstraction.
  - `Future<NavigationResult> navigatePlayer(OdysseyPlayer)` in which we ultimately use the `OdysseyPlayer` to get the Modes we can use based on the abilities the Player has. The most important one is `bool canFly()` -- which would tell us whether we should include the `FLY` mode
  - `void registerTunnelProvider(TunnelProvider)`: where `TunnelProvider` is a functional interface for `Future<List<? extends Tunnel>> compute(Player)` so we get a list of tunnels given a player. Developers may use this API to register their own means of teleportation like custom portals or commands that immediately teleport them somewhere, and it can be evaluated lazily and asynchronously.

If needed, the API classes can have generics on objects like Tunnels and Players so we may construct and use them downstream in platform-specific libraries with strongly-typed objects. Never downcast anywhere in this project.

## `minecraft` subproject
- MinecraftMode implementations
  - `WalkMode`
  - `JumpMode`
  - `SwimMode`
- `OdysseyBlock`: an abstract minecraft "Block". This will provide a whole host of methods that request information about the blocks we are dealing with, which a given platform will actually implement.
  - `isWater()`
  - `isDangerous()` (will this block damage me? Should be considered untraversable or perhaps later we will assign custom cost to this based on how much damage per second it does or something)
  - `isHalfBlock()` (for determining whether we can step-up)
  - `isEnterable(Direction)` (air is enterable from all directions, but carpets are only enterable from everywhere but underneath)
  - `isExitable(Direction)` (similar to the above)
  - `isClimbable()` true for ladders and vines. Although vines are also passable, so in order to climb the vines must be up against a wall... Twisted vines and scaffolds are more interesting because one can simply press the spacebar and "climb" them by seemingly just floating upwards.
  - Doors and trapdoors, and any other interactable thing is interesting because it's properties can change and it can depend on a player's ability to interact with it. Iron doors, for example, require a button near it. Complex cases like this deserve their own Modes.
- `OdysseyChunk`: an abstract minecraft "Chunk" (16x16 region of space that extends to block-height. The atomic unit for world rendering in Minecraft.)
  - `OdysseyBlock getBlock(x, y, z)` Get block from a chunk snapshot -- x and z correspond to index 0-15 inside the chunk itself
- `OdysseyWorld`: an abstract minecraft "World"
- `OdysseyPlayer`: an interface of a player -- platform agnostic. This will be implemented as a wrapper around a Player type from the API platforms, but it could also be an animal in situations where an implementation of Odyssey could be a plugin that creates "guide" animals that traverse calculated paths to lead the player. An Agent provides their own Modes.
- ChunkProvider: See below -- modes will request blocks from a provided ChunkProvider.
- `PlatformApi`: An API service that is implemented by any given "minecraft platform" implementation subproject. We use this API internally to "connect" our decision logic to real Minecraft concepts, like Blocks, by later hooking them into the platform API like Paper/Folia or Sponge.
  - `Future<OdysseyChunk> getChunk(Cell, Domain)` the future may complete on any thread
  - `Future<OdysseyBlock> getBlock(Cell, Domain)` helper function that uses `getChunk` -> `OdysseyChunk::getBlock` from chunk we get
- `Agent`: usually a `Player`, but an agent is generally anything in Minecraft that can move around, supplies the modes that it has access to, has permissibility for things (`hasPermission(String)`), and maybe some other things. 
- `OdysseyPlayer`: derives from `Agent`, has unique UUID, plugin platform implementations will implement this with their own type as a wrapper around their own Player type.

Note how the movement algorithm we write to implement some Modes will dictate what kind of abstractions we need from OdysseyBlock. `isEnterable(Direction)` is one option that leans heavily on the nature that Cells (1x1x1 block coordinates) are the atomic unit of movement, but that's not technically true in Minecraft. A player can occupy the space inside of a Cauldron for example, which is like a giant bowl with the inside enterable from the top. The ability for a player to enter a cauldron is hardly useful, however -- we are almost always only interested in the ability for a player to enter and then exit a block in a different direction. So, a more interesting question would be how to abstract a "fence" structure. If two fences are next to each other, they connect to form a wall of sorts. But if the fences are diagonal from each other, they do not connect and a player may pass through the diagonal space between them. So fences are sometimes "passable" but sometimes "impassable". I will leave the initial implementation for you to be fairly simple but I open to suggestions.

I've been vague with the "cost" of a movement step. In general, the cost should actually just be the time it takes for the user to get to a destination. I would like to brainstorm other ways that we can add external registrations to alter what this cost function looks like to incentivize or deincentivize different types of movement. Perhaps in a survival server, we want to really deincentivize going through the Nether, unless it's night time, in which case it might be safer to go through the Nether. For now, though, let's stick to just time-of-traversal as the cost.

### Chunk Provider
Modes will need to get what Blocks are at given Cell coordinates in a given Domain. In general, Minecraft runs synchronously on a single thread. This means that if any Block is needed, that request must be made on the main server thread. So in order to run Odyssey searches efficiently, most of the algorithm should run on an asynchronous (not main server) thread but make calls to queue synchronous requests to get blocks that asynchronously reschedule continued work back on the asynchronous threads. For Folia, this is not technically true -- there is no single "main" server thread. Instead, the world is segmented into distinct managed regions that may be running on the same thread but also may be separated into multiple threads for improved performance where players are highly localized such as in SkyBlock servers or perhaps just very large survival servers where the maps are very large and players are grouped into towns. Functionally, this doesn't change anything for our Chunk Provider from the Mode perspective, but it will change the implementation.

Additionally, Minecraft treats Chunks as its atomic load unit, which is a 16x16 X-Z coordinate region of space that extends through the entire block height of the world (-64 Y to 128 Y in overworld. A Domain should provide what its min and max height is.). Most platforms provide a way to copy a "live" chunk loaded into memory on the main server thread to a "snapshot" that just contains immutable memory about the chunk and can be used and copied off the main server thread. This is what OdysseyChunk uses under the hood -- whatever the "snapshot" type of chunk the platform API provides.

So the ChunkProvider has an LRU cache internally of a configurable size. The entries are chunk snapshots (OdysseyChunks) and requests any chunk snapshots for blocks that it doesn't have through a prpxy PlatformApi. The cache should be thread-safe. Also, "stale" chunks should be evicted if a block from a stale chunk is discovered. The "staleness" age is passed in and will be configurable. By default, probably something like 10 seconds. Also, for caching purposes, we should actually request not only the chunk for the block that is currently request, but also make a request for adjacent chunks in preparation for them to maybe be needed on a subsequent `getChunk` request by the mode needing another chunk soon. This is under the assumption that the A* algorithm will request blocks in a line generally towards the destination, so needing block at X = 0 means we will likely need next block at X = -1 or X = 1, linearly speaking. Let's start with a heuristic of -- if the block requested from a chunk is within 4 blocks of the border of the chunk, the next adjacent chunk in that direction will be requested. We will do testing on a real Minecraft world to see if this is a good enough heuristic -- we may need to be more aggressive and request the next chunks over by a distance of 16 or something larger, so lets just make it easily configurable by a given distance. A higher number would mean potentially having chunks more readily available for faster search times but can mean more memory because we're more often fetching chunks we don't end up using.

## Platform API subprojects
For developers (and indeed ourselves when making integration plugins), it will be very useful to have platform-specific API access points. So Paper-specific plugins can easily use Paper-object terminology when requesting things, like a simple `navigatePlayer(Player, Location)` where `Player` is an actual PaperMC Minecraft player object and `Location` is a real `World`-based location with x, y, and z coordinates. We will internally provide implementations that hook PlayerMC `Player` to our Agent infrastructure with Modes that make sense for the current Player attributes. Also, `registerNavigatorFactory` should have platform-specific implementations as well, since using `OdysseyPlayer` as an input to operate on is highly restrictive for anyone that isn't Odyssey developers, as we can change that interface at will to support any other internal navigator we want!
- PaperMC
  - `PaperOdysseyAPI`
- Sponge
  - `SpongeOdysseyAPI`
- Fabric
  - `FabricOdysseyAPI`
  - Let's skip Fabric for now, I'm actually not sure how applicable Odyssey is to this yet
- Each will have their own versions of the API methods in the generic `minecraft-api` library, like registering tunnel providers or navigating a player.

## Platform (implementations)
This is where we implement `PaperOdysseyAPI` and other platform APIs with `PaperOdysseyAPIImpl`... etc. These implementations have wrappers for their objects like `papermc.Paper` -> `OdysseyPaper` and implements the methods we need with the underlying `Player` for `canFly()` or whatever else we need for the abstract Odyssey Player type.
This is also how we implement things like `getChunk(Cell)` because ultimately we need to request from the platform itself the chunks asynchronously from the server thread and then wrap them into a `OdysseyChunk` to give to Odyssey for the algorithm. `Block` will be wrapped to implement `OdysseyBlock` as well, etc.

## Minecraft Plugin API
- Depends on Kyori Adventure API: all endpoint projects will require kyori adventure implementation. This is just an ease-of-use benefit to support it at this high core-api level. This allows us to have readable "Component" messages as the names of Destinations, etc, to essentially natively support rich text in Odyssey.
  - registerDestinationProvider(): Registers a provider of destinations. This is primarily useful for plugin integrations that have "places of interest" that players will potentially want to navigate to. The object that a developer will pass in is a `DestinationTree`. See below.
  - `registerNavigatorFactory(NavigatorFactory)` see Navigators below.

### Destination Trees
In a previous project, I called these "scopes" because destinations are concepts that are scoped to different contexual scenarios. See Command Structure below for more details on how this is used.

Basically, a developer may register a destination tree into `registerDestinationProvider(DestinationProvider)`. A tree can have nested subtrees of its own and also have destinations. Each subtree and destination are keyed under unique strings (upper case is allowed, special characters are not, spaces are allowed but discouraged since in the command structure the argument must then be wrapped in quotation marks). The subtrees and destinations in the maps must actually each be as effectively a `Supplier`s to those values. The reason is because the quantity of these could be actually be very large, so we only want to materialize all the possibilities once we know this current node in the tree is being considered. (Usability-wise, we want to also support a constructor with a fixed map, but we will internally convert that to a supplier to the fixed map.)

The root `DestinationProvider` is a functional interface for `DestinationTree provide(Agent)`, since `Agent`

Each "Destination" will really be a `MinecraftDestination` as a wrapper around our base `Destination` type in the Core library. These Minecraft ones also have display names (`Component` from Kyori Adventure API) and a list of Permissions (`String`) that all must be met for a given Player to be allowed to use it. (Our generic `Player` type must have a `hasPermission()` field to support this.)

Since the subtrees and destinations are evaluated lazily when it's called with specific players

### Navigation API
We also want to create an interface for creating "navigators". These are the things that run to somehow display to a user how to traverse the calculated PathString. This is usually highly Minecraft-specific. The simplest and probably most common one should be a `TrailNavigator`, in which the next few blocks (100 or so, should be configurable) are buffered into a set of cells in which particles are repeatedly rendered. Keep in mind in this library, we don't have access to actually render the particles so we must proxy these requests to the platform-implementation API (`PlatformAPI` above, with a method like `displayParticle(String type, int quantity)`). Navigators should be constructable by some sort of factory method that takes in a `OdysseyPlayer` and a `PathString`, since ultimately we will supply navigators as options to players by having them registered as a factory method. This way, other developers can implement their own factories that create other navigators on behalf of the player. It is our job to manage these in a map keyed by some unique String (to lowercase) and to manage any player's current navigations. (this term is confusing since Navigation also refers to the search of a PathString. Open to suggestion). A player may have multiple navigations. 

## Minecraft Plugin
- Configuration file defined here and this subproject will depend on a configuration management library for YML files. We will also have command structure defined here

### Configuration
In the project resources of this library, we will have a well document `config.yml` file that has all sorts of configurations we will need for server administrators with Odyssey installed. Each parameter and section should be well documented.

Paired with the config file, I'd like to have a Java configuration receiver where we have "registered" parameters that have a well defined key (period-deliminted string like "navigators.trail.particle_type") (use snake case for parameter names) and some parameters should be "mutable" and when a `reload()` function is called on the configuration manager, all mutable parameters will be updated with the new value in the yaml file. However, immutable parameters will not be allowed to change and will warn the user via an WARN logging message that the parameter was changed in the config but a restart is required for it to take effect.

### Vanilla Tunnels
There are ways for players to "teleport" in "vanilla" minecraft (without additional plugins). The most common of these are Nether Portals and End Portals. We should manage these internally and store the results in some sort of persistent data storage. However, no platform API will be able to tell us where a nether portal goes since Minecraft decides it internally and may even spawn a nether portal on the other side if one didn't already exist on the other side. So, we should take a discovery approach. Once a player enters a nether portal, we will identify all the blocks that make up the nether portal on either end and create an internal Tunnel and store it in our database. The other direction might not be guaranteed to work (they might teleport back to a different portal, I know it's weird. It has to do with the block distance calculations of "equivalent" location in nether vs overworld), so we must wait for a player to enter through the other way to create a Tunnel going the other direction. Tunnels are one way, FYI, it that wasn't already clear. The same process applies to end portals.

### Data Managers
We need support for multiple types of data managers. We should support MySQL, PostgreSQL, H2, SQLite, and MongoDB. We need an abstract interface for all of our persistent data storage and the server administrator should have a configuration option to change what their backend is and the location and credentials for that backend.

### Command Structure Support
Though the actual command structure, like arguments and flags, will be implemented in the minecraft-plugin implementation libraries, we will have some helper methods in this library. For example, from the `/navigate` command, players may specify the destination they want to go to. However, the name of the destination is dependent on the registered "destination providers" that other plugin developers or indeed our own other-plugin-integration plugins may implement. So, if the Essentials integration plugin registers a Destination Tree with name `essentials` and with "home" as a viable destination, the "tree" is `essentials -> <home>` when the tree is requested by any given Player with a home. From a command perspective, the simplest option is to provide `/nav essentials home` as the command, so we should hook each destination to the end of a string of arguments that are possibly entered by the user. However, if `essentials` is the only plugin that we have, then it would be great if our users can simply say `/nav home`. So, to support this, if there are no conflicts with other names at the middle or leaf levels of different trees, the names of the destinations can be moved up to a level closer to the root level. The developer of a DestinationTree should also report at various levels of the trees whether they are "strict" or not, meaning that that level may never be omited for the sake of simplicity. So here in this library, we should have a method to use in the command structures that calculates (by traversing the destination trees) which destinations should be reachable at the current node directly with that destination ID regardless of the intermediary subtree ids.

## Minecraft Platform Plugins
The most downstream subprojects are these. They will inherit from their respective platform implementation subproject and also the `minecraft-plugin` subproject to tie in the command structure, config file initialization, and any other required boilerplate to get the plugin in the correct format and usable.
- Paper/Folia plugin
- Sponge plugin

FYI we might eventually have to make multiple Sponge instances since Sponge specifically has multiple somewhat distinct APIs. Most things don't change betwen API versions but when they do, we will either want to make new subprojects for them to support the distinctly or have some sort of cross-version support that loads the proper API files depending on what version we discover when the server starts.

### Command structure
Commands in Minecraft plugins are designed as a tree, and they start with a forward slash `/`. The base command should be `/navigate` with an alias for `/nav`. See #Command Structure Support for where we will put shared code in the generic `minecraft-plugin` subproject. We will need to enable users to make whatever customizations make sense to their searches. Flags are the strongest approach (`-navigator <navigator-id> -no-world <world-name> -no-dimension <dimension-type> -no-mode <mode-id>`), where dimensions are things like "the_nether" or modes that you can ignore are like "fly". Aliases can be useful too, like `-no-fly` would be an alias for `-no-mode fly`.

There should also be admin commands that is under the root command `/odysseus`. Included in this should be `/odysseus reload` which reloads the configuration. There should also be a portal command for managing the internal cache of vanilla tunnels -- `/odysseus portals clear` should clear the cache, for use cases where Nether portals are linking in a weird or buggy way or the cache has gotten too big or something.

## Minecraft Plugin Integrations
Each of these are additional plugins that we should make. They're only purpose is, upon startup, connect the given plugin to Odysseus by registering Tunnel, Navigator, or Destination providers so Odysseus can be used in the larger Minecraft plugin ecosystem.
- OdysseusCitizens
  - Citizens is a plugin that provides an API to puppet entities in Minecraft. Dummy players can be spawned and told to move around, for example, or even animals like Foxes or Turtles. The integration plugin creates a Navigator with the id `guide`, which uses the `PathString` from the Navigation Result after a search to spawn an animal of the player's choosing to "guide" the player to the destination. This requires some special logic to spawn the entity, tell it navigate to a certain number of blocks ahead of the player in the current path, wait for the player to get close enough before continuing to walk along the path, teleporting through Tunnels if there are any, and finally despawning when the destination is reached.
- OdysseusEssentials
  - Essentials is a plugin that provides `/spawn` and `/home` for players, which are methods of teleportation. These should be provided to players via `DestinationProvider` in appropriate subtrees and appropriate IDs and names in addition to `TunnelProvider` for teleporting as long as the player has the Essentials permission to actually perform the teleport.
- OdysseusTowny
  - Towny is a plugin that allows players to great "Towns" that have chunks claimed as chunks for a town. Players should be able to navigate to any of the towns on the server, so OdysseusTowny provides them in a DestinationProvider.
- Quest plugins:
  - All of the following integration plugins are integrations for quest plugins. These are plugins that allow the server owner to register quests for players to explore. We should add `DestinationProviders` anywhere necessary like whatever the players' "current quest" destination is.
  - OdysseusTypewriter: (Typewriter: gabby235)
  - OdysseusQuests: (Quests: PikaMug)
  - OdysseusNotQuests: (NotQuests: Alessio)
  - OdysseusBetonQuest: (BetonQuest: Wolf2323)

## Core Test
This is analagous to the Minecraft libraries. We should define some simple modes like `FLY`, `WALK`, and `DIG`. Each one can move in straight cardinal directions, diagonal in any two dimensions, or diagonal on all three dimensions. However, walking means you can only move if there is a free path to get there and the destination step has a solid block below. So if going diagonally, there has to actually be air 

We should also define block types. For now, let's just create two -- SolidBlock and AirBlock. This is what our chunks will be made of.

We also need a simple "World" data format that has easy builder methods to construct our worlds with all the barriers and contours that would make for an interesting Navigation. We should probably have the world format be defined in JSON as a list of prismatic regions, each with two 3D coordinates to define the region and then a block type. Then, when performing tests or when visualizing the world and the result of the Navigation, we will load these JSON world descriptions into a Java "WorldManager" and it will have some helper functions that are needed for testing and visualization. The manager should have `getWorlds()` and each return `World` should have what the Modes need to perform each step like `getBlockType(Cell)`. 

In the test package for this subproject, we will have our actual unit tests to hardcode the results of various iterations of the search algorithm. We will import the worlds and then run an expansive series of tests to make sure that the PathStrings returned make sense. For example, we should check straight shots through a world of air with only `FLY` mode. We should check that we go around a wall if we don't have the `DIG` mode available and there is a wall between the origin and destination. We should be able to go through a solid wall if we have `DIG` mode and the cost to dig through a block is less than it takes to walk around the wall. We should also have failure cases, like if there is no way to reach a world via any Tunnel, or if the destination is walled off and we don't have `DIG` mode.

## Playground
Depends on the core-test library and also a visualization library like JavaFX 3D. We will want to have some basic support for having a camera move around the 3D space with basic WASD controls and visualize the calculated PathStrings using red lines through the calculated zones. It would also be helpful to turn off-and-on "solid" block opacity because some PathStrings depending on our Mode implementations will take us through "solid" blocks (to simulate Minecraft `MINE` Mode). Also, multiple "Domains" will be hard to visualize in one 3D space, so let's have them rendered next to each other with a "gap" of air in between and mark Tunnels with appropriate shapes like cylinder of rings to illistrate warping in Minecraft.

## Metrics
We should add support for bStats (Minecraft-specific metrics tracking library) and Prometheus (for server owners to track current usage of Journey). bStats metrics should include:
- current navigations being searched
- current active navigators (players currently being navigated along a previously calculated PathString)
- number of blocks traversed with navigators per hour (increment every block a player walks while navigating, reset every hour)
- anything else you want

Prometheus should support:
- everything bStats supports
- algorithm stats
  - cells currently in "visited" set during A* search
  - cells currently in "destination" set during A* search

## More ideas/questions
highways: a future feature that would allow us to cache in-database a rough line-string-based compression of certain "highway" fast-track regions, like railways or custom plugin speed-inducing runways. We would perform preliminary discovery and caching on these regions and supply them as VirtualPaths with much improved costs.
add CONTRIBUTING.MD file
add README.MD file
Falling... how does a mode support that? We can only fall like 3 or 4 blocks in Minecraft before taking damage and that should be avoided. Also, though, any distance falling into water is safe, so that should be allowed. 
Every subproject needs to have its code in a unique subpackage so when we zip it all together in an uberjar, the files fit neatly together.
"Sponge" API should be called `sponge-16` since we're targeting Sponge 16 first.
GradleUp plugin for shadowing
Watch out for what should be asynchronous or synchronous with the main server thread. Most requests to ask about the current state of the server must be scheduled onto the server thread loop and our searches run on asynchronous worker threads.
I may have some designed components in conflicting locations, but in general, place everything that can be made abstract in the most abstract library possible, potentially adding generics, so we can reuse code and avoid downcasting anywhere.
Versioning... this is difficult because we might make a change to an integration plugin to fix something but not make changes to the core library. In general, any changes to the API libraries should warrant a minor version upgrade and any other changes warrant a patch-version update. We will only publish new versions where appropriate though for the implementation libraries.

## design notes
checkstyle: make any changes that make sense to the checkstyle, but I want our code to be managed by linting too
Do not do casting checks if possible, everything should be strong-typed and heavily use polymorphism and class heirarchy to perform ancestor-related operations.
Anything that can be pushed up into more abstract libraries should
This document should basically contain the design for the entire project

## External resources
- PaperMC API
- SpongeAPI
- Citizens API
- Essentials API
- Towny API
- Typewriter API
- Quests API
- NotQuests API
- BetonQuest API