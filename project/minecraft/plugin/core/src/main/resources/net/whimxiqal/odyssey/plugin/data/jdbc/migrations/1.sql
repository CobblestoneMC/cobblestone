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
