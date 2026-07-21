-- from types/nested/array/array_aggregate.test:5
PRAGMA enable_verification;

-- from types/nested/array/array_aggregate.test:8
CREATE TABLE tbl1 (a INT[3]);

-- from types/nested/array/array_aggregate.test:11
INSERT INTO tbl1 VALUES ([1, 2, 3]), ([4, NULL, 6]), ([7, 8, 9]), (NULL), ([10, 11, 12]);

-- from types/nested/array/array_aggregate.test:14
SELECT FIRST(a ORDER BY ALL), LAST(a ORDER BY ALL) FROM tbl1;
