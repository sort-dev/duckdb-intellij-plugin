-- from delete/cleanup_delete_on_conflict.test:5
CREATE TABLE tbl(i INTEGER);

-- from delete/cleanup_delete_on_conflict.test:8
INSERT INTO tbl FROM range(1000) t(i);

-- from delete/cleanup_delete_on_conflict.test:11
SET immediate_transaction_mode=true;

-- from delete/cleanup_delete_on_conflict.test:20
DELETE FROM tbl WHERE i BETWEEN 200 AND 300;
