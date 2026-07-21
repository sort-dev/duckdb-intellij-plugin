-- from index/art/create_drop/test_art_create_if_exists.test:5
PRAGMA enable_verification;

-- from index/art/create_drop/test_art_create_if_exists.test:8
PRAGMA immediate_transaction_mode = True;

-- from index/art/create_drop/test_art_create_if_exists.test:11
CREATE TABLE tbl AS SELECT range AS i FROM range(100);

-- from index/art/create_drop/test_art_create_if_exists.test:19
CREATE INDEX IF NOT EXISTS my_idx ON tbl(i);
