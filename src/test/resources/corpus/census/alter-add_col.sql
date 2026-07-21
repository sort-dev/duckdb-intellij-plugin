-- from alter/add_col/test_add_col.test:5
CREATE TABLE test(i INTEGER, j INTEGER);

-- from alter/add_col/test_add_col.test:8
INSERT INTO test VALUES (1, 1), (2, 2);

-- from alter/add_col/test_add_col.test:11
ALTER TABLE test ADD COLUMN k INTEGER;

-- from alter/add_col/test_add_col.test:14
SELECT * FROM test;
