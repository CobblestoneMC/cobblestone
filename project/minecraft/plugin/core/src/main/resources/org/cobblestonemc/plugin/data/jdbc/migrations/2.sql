-- Last death location, one row per player, so a player can navigate back to where they died.
-- Keyed by the player, so each death UPDATES the row rather than accumulating a history.
CREATE TABLE cobblestone_death (
  player CHAR(36) NOT NULL,
  world VARCHAR(255) NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  PRIMARY KEY (player)
);
