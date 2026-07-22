-- from index/art/types/test_art_boolean.test:5
CREATE TABLE t0(c0 BOOLEAN, c1 INT);

-- from index/art/types/test_art_boolean.test:8
CREATE INDEX i0 ON t0(c1, c0);

-- from index/art/types/test_art_boolean.test:11
INSERT INTO t0(c1) VALUES (0);

-- from index/art/types/test_art_boolean.test:14
SELECT * FROM t0;
