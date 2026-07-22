-- from transactions/aborted_transaction_commit.test:5
CREATE TABLE keys(i INTEGER PRIMARY KEY);

-- from transactions/aborted_transaction_commit.test:11
INSERT INTO keys VALUES (1);

-- from transactions/aborted_transaction_commit.test:23
SELECT COUNT(*) FROM keys;

-- from transactions/concurrent_drop_index.test:7
SET immediate_transaction_mode=true;
