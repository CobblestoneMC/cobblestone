-- A player with no row uses the defaults.
CREATE TABLE cobblestone_player_preferences (
  player CHAR(36) NOT NULL,
  hud BOOLEAN NOT NULL,
  PRIMARY KEY (player)
);
