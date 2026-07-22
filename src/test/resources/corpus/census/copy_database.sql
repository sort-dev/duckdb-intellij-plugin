-- from copy_database/copy_database_different_types.test:5
ATTACH '/tmp/duckdb_test/copy_database_different_types.db' AS db1;

-- from copy_database/copy_database_different_types.test:8
USE db1;

-- from copy_database/copy_database_different_types.test:11
CREATE TABLE test(a INTEGER, b INTEGER, c VARCHAR(10));

-- from copy_database/copy_database_different_types.test:14
INSERT INTO test VALUES (42, 88, 'hello');
