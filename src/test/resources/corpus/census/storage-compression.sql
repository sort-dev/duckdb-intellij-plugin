-- from storage/compression/simple_compression.test:14
CREATE TABLE test (a INTEGER, b INTEGER);

-- from storage/compression/simple_compression.test:17
INSERT INTO test VALUES (11, 22), (11, 22), (12, 21), (NULL, NULL);

-- from storage/compression/simple_compression.test:20
SELECT SUM(a), SUM(b) FROM test;

-- from storage/compression/simple_compression.test:32
DROP TABLE test;
