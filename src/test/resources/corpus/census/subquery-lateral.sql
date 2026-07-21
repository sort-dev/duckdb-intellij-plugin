-- from subquery/lateral/lateral_arrays.test:5
PRAGMA enable_verification;

-- from subquery/lateral/lateral_arrays.test:8
CREATE TABLE tbl(i INTEGER, arr INT[]);

-- from subquery/lateral/lateral_arrays.test:11
INSERT INTO tbl VALUES (1, ARRAY[1, 3, 7]), (2, ARRAY[8, NULL]), (3, ARRAY[3, NULL, 4]), (NULL, ARRAY[]::INT[]);

-- from subquery/lateral/lateral_arrays.test:14
SELECT * FROM tbl JOIN LATERAL (SELECT UNNEST(tbl.arr)) t(b) ON (i=b) ORDER BY i;
