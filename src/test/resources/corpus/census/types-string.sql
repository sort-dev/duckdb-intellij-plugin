-- from types/string/test_big_strings.test:5
CREATE TABLE test (a VARCHAR);

-- from types/string/test_big_strings.test:9
INSERT INTO test VALUES ('aaaaaaaaaa');

-- from types/string/test_big_strings.test:13
INSERT INTO test SELECT a||a||a||a||a||a||a||a||a||a FROM test WHERE LENGTH(a)=(SELECT MAX(LENGTH(a)) FROM test);

-- from types/string/test_big_strings.test:22
SELECT LENGTH(a) FROM test ORDER BY 1;
