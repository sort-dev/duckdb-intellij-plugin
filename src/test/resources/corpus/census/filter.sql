-- from filter/filter_cache.test:5
PRAGMA enable_verification;

-- from filter/filter_cache.test:8
CREATE TABLE integers AS SELECT a FROM generate_series(0, 9999, 1) tbl(a), generate_series(0, 9, 1) tbl2(b);

-- from filter/filter_cache.test:11
SELECT COUNT(*) FROM integers WHERE a<5;

-- from filter/filter_cache.test:16
SELECT COUNT(*) FROM (SELECT * FROM integers WHERE (a>1 AND a<10) OR a>9995) tbl(a) WHERE a<5;
