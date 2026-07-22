-- from storage/optimistic_write/optimistic_write_delete.test:7
CREATE TABLE test (a INTEGER);

-- from storage/optimistic_write/optimistic_write_delete.test:10
BEGIN TRANSACTION;

-- from storage/optimistic_write/optimistic_write_delete.test:13
INSERT INTO test SELECT * FROM range(1000000);

-- from storage/optimistic_write/optimistic_write_delete.test:16
DELETE FROM test WHERE a=0;
