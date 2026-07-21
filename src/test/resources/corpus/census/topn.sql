-- from topn/test_top_n.test:5
PRAGMA enable_verification;

-- from topn/test_top_n.test:8
CREATE TABLE test (b INTEGER);

-- from topn/test_top_n.test:11
INSERT INTO test VALUES (22), (2), (7);

-- from topn/test_top_n.test:15
SELECT b FROM test ORDER BY b DESC LIMIT 2;
