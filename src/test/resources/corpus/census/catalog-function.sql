-- from catalog/function/information_schema_macro.test:5
PRAGMA enable_verification;

-- from catalog/function/macro_query_table.test:8
create macro min_from_tbl(tbl, col) as (select min(col) from query_table(tbl::VARCHAR));

-- from catalog/function/macro_query_table.test:11
create table integers as from range(100) t(i);

-- from catalog/function/macro_query_table.test:14
SELECT min_from_tbl(integers, i);
