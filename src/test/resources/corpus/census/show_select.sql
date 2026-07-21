-- from show_select/describe_subquery.test:5
PRAGMA enable_verification;

-- from show_select/describe_subquery.test:8
SELECT column_name FROM (DESCRIBE SELECT 42 AS a);

-- from show_select/describe_subquery.test:13
SELECT t.column_name FROM (DESCRIBE SELECT 42 AS a) t;

-- from show_select/describe_subquery.test:18
(DESCRIBE SELECT 42 AS a);
