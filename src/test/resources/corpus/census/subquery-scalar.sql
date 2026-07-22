-- from subquery/scalar/array_order_subquery.test:5
create table t (i int);

-- from subquery/scalar/array_order_subquery.test:8
insert into t values (1),(2),(3),(4),(4);

-- from subquery/scalar/array_order_subquery.test:11
select
  array(select distinct i from t order by i desc) as a,
  array(select distinct i from t order by i desc) as b,
  array(select distinct i from t order by i desc) as c;

-- from subquery/scalar/array_order_subquery.test:20
select array(select unnest(l) AS i order by i desc nulls last) as a from (values ([NULL, 1, 2, 3, 4]), ([5, 6, NULL, 7, 8]), ([]), ([10, 11, 12])) t(l);
