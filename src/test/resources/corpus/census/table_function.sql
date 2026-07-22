-- from table_function/database_oid.test:5
CREATE TEMP TABLE x (x INT);

-- from table_function/database_oid.test:8
CREATE TABLE x (x INT);

-- from table_function/database_oid.test:11
SELECT COUNT(DISTINCT database_oid) FROM duckdb_tables();

-- from table_function/duckdb_columns.test:5
set storage_compatibility_version='v0.10.2';
