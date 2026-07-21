-- from join/set_operators/test_set_operator_reordering_with_delim_joins.test:5
create or replace table xx as select w from (values ('a'),('b'),('c'),('d'),('e')) t(w);

-- from join/set_operators/test_set_operator_reordering_with_delim_joins.test:8
select w from (from xx limit 4)
CROSS JOIN (select 1 as f1) p
WHERE
   w IN (
	  SELECT 'a'
	  UNION -- with 'UNION ALL' it works also using 'limit 4'
	  SELECT 'b'
	  UNION
	  SELECT 'c' WHERE p.f1 = 1
	  UNION
	  SELECT 'd' WHERE p.f1 = 1
);
