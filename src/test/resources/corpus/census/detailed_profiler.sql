-- from detailed_profiler/test_detailed_profiler.test:5
PRAGMA enable_profiling;

-- from detailed_profiler/test_detailed_profiler.test:8
PRAGMA profiling_output='/tmp/duckdb_test/test.txt';

-- from detailed_profiler/test_detailed_profiler.test:11
PRAGMA profiling_mode = detailed;

-- from detailed_profiler/test_detailed_profiler.test:14
CREATE TABLE integers(i INTEGER);
