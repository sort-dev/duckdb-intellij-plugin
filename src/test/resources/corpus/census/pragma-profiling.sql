-- from pragma/profiling/test_duckdb_profiling_settings_function.test:5
SELECT * EXCLUDE(value, description) FROM duckdb_profiling_settings();

-- from pragma/profiling/test_duckdb_profiling_settings_function.test:14
PRAGMA enable_profiling='json';

-- from pragma/profiling/test_duckdb_profiling_settings_function.test:17
PRAGMA profiling_output='/tmp/duckdb_test/test_profiling_output.json';

-- from pragma/profiling/test_duckdb_profiling_settings_function.test:20
PRAGMA profiling_coverage='ALL';
