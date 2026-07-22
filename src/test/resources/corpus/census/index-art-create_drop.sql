-- from index/art/create_drop/test_art_big_compound_key.test:5
CREATE TABLE v0 (v2 VARCHAR, v1 INT);

-- from index/art/create_drop/test_art_big_compound_key.test:8
INSERT INTO v0 (v2 ,v1 ) VALUES ('358677 4 2 1', 7), ('a%', 1);

-- from index/art/create_drop/test_art_big_compound_key.test:11
CREATE UNIQUE INDEX v3 ON v0
	(v1, v1, v1, v1, v1, v2, v1, v2, v1, v2, v2, v1, v2, v2,
	v2, v2, v2, v2, v1, v1, v2, v2, v1, v1, v2, v1);

-- from index/art/create_drop/test_art_create_if_exists.test:5
PRAGMA enable_verification;
