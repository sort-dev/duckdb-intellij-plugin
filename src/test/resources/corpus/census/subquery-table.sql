-- from subquery/table/test_aliasing.test:5
PRAGMA enable_verification;

-- from subquery/table/test_aliasing.test:8
create table a(i integer);

-- from subquery/table/test_aliasing.test:11
insert into a values (42);

-- from subquery/table/test_aliasing.test:14
select * from (select i as j from a group by j) sq1 where j = 42;
