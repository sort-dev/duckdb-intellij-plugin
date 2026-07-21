-- from copy/csv/overwrite/test_copy_overwrite.test:5
PRAGMA enable_verification;

-- from copy/csv/overwrite/test_copy_overwrite.test:9
CREATE TABLE test (a INTEGER, b VARCHAR(10));

-- from copy/csv/overwrite/test_copy_overwrite.test:12
INSERT INTO test VALUES (1, 'hello'), (2, 'world '), (3, ' xx');

-- from copy/csv/overwrite/test_copy_overwrite.test:15
SELECT * FROM test ORDER BY 1;
