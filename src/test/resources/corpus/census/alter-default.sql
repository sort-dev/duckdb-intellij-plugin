-- from alter/default/drop_default.test:5
PRAGMA enable_verification;

-- from alter/default/drop_default.test:8
CREATE TABLE data(id INTEGER, x INTEGER);

-- from alter/default/drop_default.test:11
ALTER TABLE data ALTER COLUMN id DROP DEFAULT;

-- from alter/default/drop_default.test:14
INSERT INTO data VALUES (1, 0), (2, 1);
