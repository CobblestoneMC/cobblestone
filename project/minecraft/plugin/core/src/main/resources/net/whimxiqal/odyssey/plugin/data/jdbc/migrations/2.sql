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
