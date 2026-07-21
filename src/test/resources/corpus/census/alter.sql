-- from alter/test_alter_if_exists.test:6
ALTER SEQUENCE IF EXISTS seq OWNED BY x;

-- from alter/test_alter_if_exists.test:10
ALTER TABLE IF EXISTS t0 ADD COLUMN c0 INT;

-- from alter/test_alter_if_exists.test:19
ALTER TABLE IF EXISTS t0 ADD COLUMN IF NOT EXISTS c0 int;

-- from alter/test_alter_if_exists.test:26
CREATE TABLE t0 (c0 INT);
