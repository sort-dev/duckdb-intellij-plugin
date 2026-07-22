-- from limit/test_batch_limit_filters.test:5
CREATE TABLE tbl AS SELECT concat('thisisastring', i) s FROM range(1_000_000) t(i);

-- from limit/test_batch_limit_filters.test:8
FROM tbl WHERE s LIKE '%string999999%' LIMIT 5;

-- from limit/test_batch_limit_filters.test:14
EXPLAIN FROM tbl WHERE s LIKE '%string999999%' LIMIT 5;

-- from limit/test_limit0.test:6
SELECT * FROM (SELECT SUM(i) FROM range(100000000000) tbl(i)) LIMIT 0;
