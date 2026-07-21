-- from alter/drop_col/test_drop_col.test:5
CREATE TABLE test(i INTEGER, j INTEGER);

-- from alter/drop_col/test_drop_col.test:8
INSERT INTO test VALUES (1, 1), (2, 2);

-- from alter/drop_col/test_drop_col.test:11
ALTER TABLE test DROP COLUMN j;

-- from alter/drop_col/test_drop_col.test:14
SELECT * FROM test;
