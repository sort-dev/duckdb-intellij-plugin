-- from parser/columns_aliases.test:5
PRAGMA enable_verification;

-- from parser/columns_aliases.test:8
CREATE TABLE integers AS SELECT 42 i, 84 j UNION ALL SELECT 13, 14;

-- from parser/columns_aliases.test:12
SELECT i, j FROM (SELECT COLUMNS(*)::VARCHAR FROM integers);

-- from parser/columns_aliases.test:18
SELECT min_i, min_j, max_i, max_j FROM (SELECT MIN(COLUMNS(*)) AS "min_\0", MAX(COLUMNS(*)) AS "max_\0" FROM integers);
