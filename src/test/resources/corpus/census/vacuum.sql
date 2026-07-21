-- from vacuum/vacuum_nested_types.test:5
CREATE TABLE test (x INT[], y AS (x || [100]));

-- from vacuum/vacuum_nested_types.test:8
ANALYZE test(x);

-- from vacuum/vacuum_nested_types.test:11
INSERT INTO test SELECT [range % 5000] FROM range(10000);
