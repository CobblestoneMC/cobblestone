-- Initial Odyssey schema.

-- Locations: per-player and global (server-wide) navigation targets.
-- Global locations are stored under a fixed sentinel owner so (owner, name) stays a NOT-NULL key.
CREATE TABLE odyssey_location (
  owner CHAR(36) NOT NULL,
  name VARCHAR(64) NOT NULL,
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  PRIMARY KEY (owner, name)
);

-- Discovered vanilla portal links: entry-plane box -> arrival point, one direction. Covers nether
-- portals (both directions, learned as players travel) and the overworld -> End portal (the fixed
-- platform). Keyed by the SOURCE portal anchor (world + minimum corner), so re-walking a portal
-- whose destination has changed UPDATES the arrival rather than adding a duplicate row.
CREATE TABLE odyssey_portal_transition (
  from_world VARCHAR(255) NOT NULL,
  min_x INTEGER NOT NULL,
  min_y INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_y INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  to_world VARCHAR(255) NOT NULL,
  to_x INTEGER NOT NULL,
  to_y INTEGER NOT NULL,
  to_z INTEGER NOT NULL,
  cost DOUBLE NOT NULL,
  PRIMARY KEY (from_world, min_x, min_y, min_z)
);

-- End-return portals: the exit portal in the End teleports each player to THEIR OWN respawn point,
-- so no fixed destination can be stored. We cache only the portal's region (in the End) and resolve
-- the destination per-player at search time from the player's respawn location. Keyed by the portal
-- anchor so re-observing it updates rather than duplicates.
CREATE TABLE odyssey_end_return_portal (
  world VARCHAR(255) NOT NULL,
  min_x INTEGER NOT NULL,
  min_y INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_y INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  cost DOUBLE NOT NULL,
  PRIMARY KEY (world, min_x, min_y, min_z)
);

-- End gateways: a learned gateway-block -> exit-point cache, keyed by the gateway block. The exit is
-- readable from the block entity, but caching it avoids fetching every gateway at search time, and a
-- later teleport updates the exit if it changed.
CREATE TABLE odyssey_end_gateway (
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  to_world VARCHAR(255) NOT NULL,
  to_x INTEGER NOT NULL,
  to_y INTEGER NOT NULL,
  to_z INTEGER NOT NULL,
  cost DOUBLE NOT NULL,
  PRIMARY KEY (world, x, y, z)
);
