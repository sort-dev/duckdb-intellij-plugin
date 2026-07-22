-- from select/test_multi_column_reference.test:5
PRAGMA enable_verification;

-- from select/test_multi_column_reference.test:28
CREATE SCHEMA test;

-- from select/test_multi_column_reference.test:31
CREATE TABLE test.tbl(col INTEGER);

-- from select/test_multi_column_reference.test:34
INSERT INTO test.tbl VALUES (1), (2), (3);
