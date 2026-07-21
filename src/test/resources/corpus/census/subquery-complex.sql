-- from subquery/complex/correlated_list_any_join.test:5
PRAGMA enable_verification;

-- from subquery/complex/correlated_list_any_join.test:8
CREATE TABLE lists(l INTEGER[]);

-- from subquery/complex/correlated_list_any_join.test:11
INSERT INTO lists VALUES (ARRAY[1]), (ARRAY[2]), (ARRAY[3]), (NULL);

-- from subquery/complex/correlated_list_any_join.test:15
SELECT l, l IN (SELECT i1.l FROM (SELECT * FROM lists i1 WHERE i1.l=lists.l) i1 JOIN generate_series(1, 2, 1) tbl(s) ON i1.l=ARRAY[tbl.s]) FROM lists ORDER BY l NULLS LAST;
