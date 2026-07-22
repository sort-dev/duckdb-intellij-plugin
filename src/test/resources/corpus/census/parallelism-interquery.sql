-- from parallelism/interquery/concurrent_appends.test:5
CREATE TABLE integers(i INTEGER);

-- from parallelism/interquery/concurrent_appends.test:10
INSERT INTO integers SELECT * FROM range(100);

-- from parallelism/interquery/concurrent_checkpoint_insert.test:6
ATTACH '/tmp/duckdb_test/bla_0.db' AS db0;

-- from parallelism/interquery/concurrent_checkpoint_insert.test:9
CREATE TABLE db0.tbl (key INTEGER PRIMARY KEY);
