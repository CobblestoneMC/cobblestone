-- The mount (horse, donkey, llama, camel, ...) each player last rode, so they can navigate back to
-- it. One row per player, replaced on each dismount; the position is updated when the mount's chunk
-- unloads, and the row is dropped if the mount dies (hence the index on mount).
CREATE TABLE cobblestone_mount (
  player CHAR(36) NOT NULL,
  mount CHAR(36) NOT NULL,
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  PRIMARY KEY (player)
);

CREATE INDEX cobblestone_mount_by_mount ON cobblestone_mount (mount);
