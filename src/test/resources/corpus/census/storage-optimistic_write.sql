-- from storage/optimistic_write/optimistic_write_delete.test:8
CREATE TABLE test (a INTEGER);

-- from storage/optimistic_write/optimistic_write_delete.test:11
BEGIN TRANSACTION;

-- from storage/optimistic_write/optimistic_write_delete.test:14
INSERT INTO test SELECT * FROM range(1000000);

-- from storage/optimistic_write/optimistic_write_delete.test:17
DELETE FROM test WHERE a=0;
