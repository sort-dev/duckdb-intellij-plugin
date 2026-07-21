-- from delete/large_deletes_transactions.test:5
PRAGMA enable_verification;

-- from delete/large_deletes_transactions.test:8
CREATE TABLE a AS SELECT * FROM range(1000000) t1(i);

-- from delete/large_deletes_transactions.test:11
BEGIN TRANSACTION;

-- from delete/large_deletes_transactions.test:14
SELECT COUNT(*) FROM a;
