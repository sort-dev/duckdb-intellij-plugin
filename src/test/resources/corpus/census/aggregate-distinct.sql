-- from aggregate/distinct/distinct_on_nulls.test:5
PRAGMA enable_verification;

-- from aggregate/distinct/distinct_on_nulls.test:8
CREATE TABLE integers(i INTEGER, j INTEGER);

-- from aggregate/distinct/distinct_on_nulls.test:11
INSERT INTO integers VALUES (2, 3), (4, 5), (2, NULL), (NULL, NULL);

-- from aggregate/distinct/distinct_on_nulls.test:14
SELECT DISTINCT ON (i) i, j FROM integers ORDER BY j;
