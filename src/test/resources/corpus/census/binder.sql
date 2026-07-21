-- from binder/alias_error_10057.test:5
PRAGMA enable_verification;

-- from binder/column_value_alias_group.test:8
create table test(a int);

-- from binder/column_value_alias_group.test:11
insert into test values (2), (1), (3);

-- from binder/column_value_alias_group.test:15
select a as "user" from test group by "user";
