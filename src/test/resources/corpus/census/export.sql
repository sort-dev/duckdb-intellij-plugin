-- from export/empty_export.test:5
PRAGMA enable_verification;

-- from export/empty_export.test:8
EXPORT DATABASE '/tmp/duckdb_test/empty_export' (FORMAT CSV);

-- from export/export_database.test:5
SET default_null_order='nulls_first';

-- from export/export_database.test:10
BEGIN TRANSACTION;
