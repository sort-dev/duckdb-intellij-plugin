-- from storage/mix/test_update_delete_string.test:9
CREATE TABLE test (a INTEGER, b STRING);

-- from storage/mix/test_update_delete_string.test:12
INSERT INTO test VALUES (NULL, 'hello'), (13, 'abcdefgh'), (12, NULL);

-- from storage/mix/test_update_delete_string.test:15
SELECT a, b FROM test ORDER BY a;

-- from storage/mix/test_update_delete_string.test:24
PRAGMA enable_verification;
