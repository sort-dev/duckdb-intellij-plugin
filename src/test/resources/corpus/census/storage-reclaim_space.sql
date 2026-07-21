-- from storage/reclaim_space/test_reclaim_space_update_large_string.test:7
PRAGMA force_checkpoint;

-- from storage/reclaim_space/test_reclaim_space_update_large_string.test:14
CREATE TABLE test (a VARCHAR);

-- from storage/reclaim_space/test_reclaim_space_update_large_string.test:18
INSERT INTO test VALUES (repeat('a', 1000000));

-- from storage/reclaim_space/test_reclaim_space_update_large_string.test:21
SELECT LENGTH(SUBSTRING(a, 0, 1000000)) FROM test;
