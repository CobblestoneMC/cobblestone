-- Initial Odyssey schema.

-- Waypoints: per-player and global (server-wide) navigation targets.
-- Global waypoints are stored under a fixed sentinel owner so (owner, name) stays a NOT-NULL key.
CREATE TABLE odyssey_waypoint (
  owner CHAR(36) NOT NULL,
  name VARCHAR(64) NOT NULL,
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  PRIMARY KEY (owner, name)
);

-- Discovered vanilla portal links: entry plane bounding box -> arrival point, one direction.
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
  cost DOUBLE NOT NULL
);
