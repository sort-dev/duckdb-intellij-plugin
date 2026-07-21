-- from aggregate/qualify/test_qualify.test:5
PRAGMA enable_verification;

-- from aggregate/qualify/test_qualify.test:8
CREATE TABLE test (a INTEGER, b INTEGER);

-- from aggregate/qualify/test_qualify.test:11
INSERT INTO test VALUES (11, 22), (13, 22), (12, 21);

-- from aggregate/qualify/test_qualify.test:14
CREATE TABLE qt (a INTEGER, b CHAR(1), c INTEGER);
