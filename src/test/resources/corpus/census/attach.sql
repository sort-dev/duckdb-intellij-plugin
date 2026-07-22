-- from attach/attach_all_types.test:5
ATTACH '/tmp/duckdb_test/attach_all_types.db' AS db1;

-- from attach/attach_all_types.test:8
CREATE TABLE db1.all_types AS SELECT * FROM test_all_types();

-- from attach/attach_all_types.test:11
SELECT * FROM test_all_types();

-- from attach/attach_all_types.test:14
SELECT * FROM db1.all_types;
