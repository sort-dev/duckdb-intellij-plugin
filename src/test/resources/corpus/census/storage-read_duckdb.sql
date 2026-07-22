-- from storage/read_duckdb/read_duckdb_basic.test:5
ATTACH '/tmp/duckdb_test/read_duckdb_test.db';

-- from storage/read_duckdb/read_duckdb_basic.test:8
CREATE TABLE read_duckdb_test.my_tbl AS SELECT 42 i;

-- from storage/read_duckdb/read_duckdb_basic.test:17
DETACH read_duckdb_test;

-- from storage/read_duckdb/read_duckdb_basic.test:20
SELECT * FROM read_duckdb('/tmp/duckdb_test/read_duckdb_test.db');
