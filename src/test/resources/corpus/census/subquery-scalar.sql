-- from subquery/scalar/array_order_subquery.test:5
PRAGMA enable_verification;

-- from subquery/scalar/array_order_subquery.test:8
create table t (i int);

-- from subquery/scalar/array_order_subquery.test:11
insert into t values (1),(2),(3),(4),(4);

-- from subquery/scalar/array_order_subquery.test:14
select
  array(select distinct i from t order by i desc) as a,
  array(select distinct i from t order by i desc) as b,
  array(select distinct i from t order by i desc) as c;
