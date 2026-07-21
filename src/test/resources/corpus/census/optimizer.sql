-- from optimizer/test_in_rewrite_rule.test:5
create table t (i integer);

-- from optimizer/test_in_rewrite_rule.test:8
insert into t values (1);

-- from optimizer/test_in_rewrite_rule.test:11
insert into t values (2);

-- from optimizer/test_in_rewrite_rule.test:15
select * from t where i in ('1','2','y');
