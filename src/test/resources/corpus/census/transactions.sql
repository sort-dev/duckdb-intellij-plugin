-- from transactions/aborted_transaction_commit.test:5
CREATE TABLE keys(i INTEGER PRIMARY KEY);

-- from transactions/aborted_transaction_commit.test:11
INSERT INTO keys VALUES (1);

-- from transactions/aborted_transaction_commit.test:23
SELECT COUNT(*) FROM keys;

-- from transactions/count_star_transactions.test:5
PRAGMA enable_verification;
