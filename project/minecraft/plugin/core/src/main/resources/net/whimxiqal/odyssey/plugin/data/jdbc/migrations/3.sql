-- End gateways, v3: a learned gateway-block -> exit-point cache, keyed by the gateway block. The
-- exit is readable from the block entity, but caching it avoids fetching every gateway at search
-- time, and a later teleport updates the exit if it changed.

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
