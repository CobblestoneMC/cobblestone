-- Per-player display preferences, one row per player who has changed any. A player with no row
-- uses the defaults.
CREATE TABLE cobblestone_player_preferences (
  player CHAR(36) NOT NULL,
  sidebar BOOLEAN NOT NULL,
  PRIMARY KEY (player)
);
