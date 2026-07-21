-- from index/art/storage/test_art_checkpoint.test:7
CREATE TABLE integers (i INTEGER PRIMARY KEY);

-- from index/art/storage/test_art_checkpoint.test:10
INSERT INTO integers VALUES (1), (2), (3), (4), (5);

-- from index/art/storage/test_art_checkpoint.test:13
CHECKPOINT;

-- from index/art/storage/test_art_checkpoint.test:26
DROP TABLE integers;
