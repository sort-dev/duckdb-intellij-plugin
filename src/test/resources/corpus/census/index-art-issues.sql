-- from index/art/issues/test_art_fuzzer.test:5
PRAGMA enable_verification;

-- from index/art/issues/test_art_fuzzer.test:9
CREATE TABLE t1 (c1 DECIMAL(4, 3));

-- from index/art/issues/test_art_fuzzer.test:12
INSERT INTO t1(c1) VALUES (1), (-0.505);

-- from index/art/issues/test_art_fuzzer.test:15
CREATE INDEX i1 ON t1 (TRY_CAST(c1 AS USMALLINT));
