-- from index/art/test_art_empty_close_range.test:5
CREATE TABLE t0(c0 DOUBLE UNIQUE);

-- from index/art/test_art_empty_close_range.test:8
SELECT c0 FROM t0 WHERE (c0 - 0 BETWEEN 0 AND 0) AND (c0 - 0 = c0);

-- from index/art/test_art_tx_update_key.test:4
CREATE TABLE test_table (id INTEGER PRIMARY KEY);

-- from index/art/test_art_tx_update_key.test:7
INSERT INTO test_table VALUES (1);
