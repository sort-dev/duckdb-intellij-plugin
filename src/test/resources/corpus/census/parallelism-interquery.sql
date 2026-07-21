-- from parallelism/interquery/concurrent_appends.test:5
CREATE TABLE integers(i INTEGER);

-- from parallelism/interquery/concurrent_appends.test:10
INSERT INTO integers SELECT * FROM range(100);

-- from parallelism/interquery/concurrent_force_checkpoint.test:8
INSERT INTO integers SELECT * FROM range(10000);

-- from parallelism/interquery/concurrent_index_scans_while_appending.test:5
CREATE TABLE integers(i INTEGER PRIMARY KEY);
