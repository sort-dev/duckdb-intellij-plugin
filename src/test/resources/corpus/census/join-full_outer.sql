-- from join/full_outer/full_outer_join_cache.test:5
PRAGMA enable_verification;

-- from join/full_outer/full_outer_join_cache.test:8
pragma verify_external;

-- from join/full_outer/full_outer_join_cache.test:11
CREATE TABLE smalltable AS SELECT 1::INTEGER a;

-- from join/full_outer/full_outer_join_cache.test:15
CREATE TABLE bigtable AS SELECT a::INTEGER a FROM generate_series(0, 9999, 1) tbl(a), generate_series(0, 9, 1) tbl2(b);
