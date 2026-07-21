-- from alter/alter_col/test_drop_not_null.test:7
PRAGMA enable_verification;

-- from alter/alter_col/test_drop_not_null.test:10
CREATE TABLE test(i INTEGER, j INTEGER NOT NULL);

-- from alter/alter_col/test_drop_not_null.test:13
INSERT INTO test VALUES (1, 1), (2, 2);

-- from alter/alter_col/test_drop_not_null.test:20
SELECT * FROM test;
