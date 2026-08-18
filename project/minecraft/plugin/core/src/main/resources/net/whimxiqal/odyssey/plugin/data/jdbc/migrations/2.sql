-- Nether portal linking, v2: a region-partition model for nether portals (which link ambiguously by
-- entry block). End portals keep using odyssey_portal_transition (region -> point, unambiguous).
-- Odyssey is pre-release, so nether links simply re-learn as players travel.

-- The nether-portal cache: every portal region we have observed, one row per portal, keyed by its
-- anchor (world + minimum corner). Destinations for a partition are the portals in the target world.
CREATE TABLE odyssey_nether_portal (
  world VARCHAR(255) NOT NULL,
  min_x INTEGER NOT NULL,
  min_y INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_y INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  PRIMARY KEY (world, min_x, min_y, min_z)
);

-- The destination partition: entering the source portal within the sub-region links to the dest
-- portal. Source and dest full extents are stored inline (referencing the cache by anchor) so reads
-- need no join. One source portal owns one row per distinct destination.
CREATE TABLE odyssey_nether_portal_link (
  from_world VARCHAR(255) NOT NULL,
  from_min_x INTEGER NOT NULL,
  from_min_y INTEGER NOT NULL,
  from_min_z INTEGER NOT NULL,
  from_max_x INTEGER NOT NULL,
  from_max_y INTEGER NOT NULL,
  from_max_z INTEGER NOT NULL,
  sub_min_x INTEGER NOT NULL,
  sub_min_y INTEGER NOT NULL,
  sub_min_z INTEGER NOT NULL,
  sub_max_x INTEGER NOT NULL,
  sub_max_y INTEGER NOT NULL,
  sub_max_z INTEGER NOT NULL,
  to_world VARCHAR(255) NOT NULL,
  to_min_x INTEGER NOT NULL,
  to_min_y INTEGER NOT NULL,
  to_min_z INTEGER NOT NULL,
  to_max_x INTEGER NOT NULL,
  to_max_y INTEGER NOT NULL,
  to_max_z INTEGER NOT NULL,
  cost DOUBLE NOT NULL
);
