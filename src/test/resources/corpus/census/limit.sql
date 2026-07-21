-- from limit/test_limit0.test:6
SELECT * FROM (SELECT SUM(i) FROM range(100000000000) tbl(i)) LIMIT 0;

-- from limit/test_limit0.test:10
PRAGMA explain_output='OPTIMIZED_ONLY';

-- from limit/test_limit0.test:13
EXPLAIN SELECT * FROM (SELECT SUM(i) FROM range(100000000000) tbl(i)) LIMIT 0;

-- from limit/test_limit0.test:16
EXPLAIN SELECT * FROM (SELECT SUM(i) FROM range(100000000000) tbl(i)) WHERE 1=0;
