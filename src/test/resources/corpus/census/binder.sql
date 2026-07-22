-- from binder/alias_error_10057.test:5
PRAGMA enable_verification;

-- from binder/alias_qualification_group_by.test:6
SELECT a % 2 AS x, COUNT(*) AS cnt
FROM (VALUES (1),(2),(3),(4)) t(a)
GROUP BY alias.x
ORDER BY x;

-- from binder/alias_qualification_group_by.test:16
SELECT (a/2)::INT AS "Half", COUNT(*) AS c
FROM (VALUES (1),(2),(3),(4)) t(a)
GROUP BY alias."Half"
ORDER BY "Half";

-- from binder/alias_qualification_group_by.test:27
CREATE TABLE alias (g INT);
