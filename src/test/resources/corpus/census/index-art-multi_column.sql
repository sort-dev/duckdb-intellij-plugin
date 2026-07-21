-- from index/art/multi_column/test_art_multi_column.test:5
PRAGMA enable_verification;

-- from index/art/multi_column/test_art_multi_column.test:8
CREATE TABLE integers(i BIGINT, j INTEGER, k VARCHAR);

-- from index/art/multi_column/test_art_multi_column.test:11
CREATE INDEX i_index ON integers using art(j);

-- from index/art/multi_column/test_art_multi_column.test:14
INSERT INTO integers VALUES (10, 1, 'hello'), (11, 2, 'world');
