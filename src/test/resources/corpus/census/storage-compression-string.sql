-- from storage/compression/string/big_strings.test:5
ATTACH '/tmp/duckdb_test/test_big_strings_new.db' AS db_v13 (STORAGE_VERSION 'v1.3.0');

-- from storage/compression/string/big_strings.test:8
ATTACH '/tmp/duckdb_test/test_big_strings_old.db' AS db_v1 (STORAGE_VERSION 'v1.0.0');

-- from storage/compression/string/big_strings.test:13
USE db_v1;

-- from storage/compression/string/big_strings.test:20
PRAGMA force_compression='fsst';
