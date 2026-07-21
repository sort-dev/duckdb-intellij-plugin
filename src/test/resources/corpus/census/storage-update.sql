-- from storage/update/test_store_null_updates.test:8
CREATE TABLE test (a INTEGER, b INTEGER);

-- from storage/update/test_store_null_updates.test:11
INSERT INTO test VALUES (11, 22), (NULL, 22), (12, 21);

-- from storage/update/test_store_null_updates.test:14
UPDATE test SET b=b+1 WHERE a=11;

-- from storage/update/test_store_null_updates.test:19
SELECT a, b FROM test ORDER BY a;
