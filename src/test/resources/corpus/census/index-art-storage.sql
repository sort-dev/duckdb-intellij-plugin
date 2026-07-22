-- from index/art/storage/test_art_buffered_replays_chunk_edges.test:7
SET wal_autocheckpoint = '1TB';

-- from index/art/storage/test_art_buffered_replays_chunk_edges.test:10
PRAGMA disable_checkpoint_on_shutdown;

-- from index/art/storage/test_art_buffered_replays_chunk_edges.test:13
CREATE TABLE tbl(i INTEGER);

-- from index/art/storage/test_art_buffered_replays_chunk_edges.test:16
CREATE UNIQUE INDEX idx_tbl_i ON tbl(i);
