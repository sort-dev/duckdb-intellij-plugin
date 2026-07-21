-- from alter/rename_col/test_rename_col.test:5
CREATE TABLE test(i INTEGER, j INTEGER);

-- from alter/rename_col/test_rename_col.test:9
ALTER TABLE test RENAME COLUMN i TO k;

-- from alter/rename_col/test_rename_col.test:12
SELECT * FROM test;

-- from alter/rename_col/test_rename_col.test:15
DROP TABLE IF EXISTS test;
