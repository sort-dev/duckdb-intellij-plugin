-- from aggregate/having/having_alias.test:5
PRAGMA enable_verification;

-- from aggregate/having/having_alias.test:8
SELECT b, sum(a) AS a
FROM (VALUES (1, 0), (1, 1)) t(a, b)
GROUP BY b
HAVING a > 0
ORDER BY ALL;

-- from aggregate/having/having_alias.test:19
create table t1(a int);

-- from aggregate/having/having_alias.test:22
insert into t1 values (42), (84);
