-- from storage/compact_block_size/block_size_with_rollback.test:5
BEGIN TRANSACTION;

-- from storage/compact_block_size/block_size_with_rollback.test:8
ATTACH '/tmp/duckdb_test/rollback.db' (BLOCK_SIZE 16384);

-- from storage/compact_block_size/block_size_with_rollback.test:11
CREATE TABLE rollback.tbl AS SELECT range AS i FROM range(100);

-- from storage/compact_block_size/block_size_with_rollback.test:14
ROLLBACK;
