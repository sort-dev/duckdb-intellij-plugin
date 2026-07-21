-- from setops/ambiguous_order_by.test:5
select * from (values(42, 84)) s1(c1, c2) union all select * from (values(84, 42)) s2(c2, c3) order by c1;

-- from setops/ambiguous_order_by.test:11
select * from (values(42, 84)) s1(c1, c2) union all select * from (values(84, 42)) s2(c2, c3) order by c3;

-- from setops/setops_pushdown.test:6
SELECT 42 WHERE 1=0 EXCEPT SELECT 42;

-- from setops/setops_pushdown.test:11
SELECT 42 EXCEPT SELECT 42 WHERE 1=0;
