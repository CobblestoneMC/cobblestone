-- The mounts (horses, donkeys, llamas, camels, ...) each player has ridden, so they can navigate
-- back to them. One row per player and mount: getting off a mount again updates its row, and
-- ridden_at (epoch millis of that dismount) orders a player's mounts newest first. Navigating to
-- "mount" means the newest one. Only that one is kept for now, but the table allows more, so
-- keeping a history later needs no new migration.
-- The position is where the mount was last seen: updated when its chunk unloads and when the server
-- stops. Both that and the drop on death are keyed by the mount alone, hence the index. Lookups by
-- player use the primary key, which leads with player.
CREATE TABLE cobblestone_mount (
  player CHAR(36) NOT NULL,
  mount CHAR(36) NOT NULL,
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  ridden_at BIGINT NOT NULL,
  PRIMARY KEY (player, mount)
);

CREATE INDEX cobblestone_mount_by_mount ON cobblestone_mount (mount);
