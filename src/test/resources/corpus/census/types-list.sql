-- from types/list/list_case.test:5
PRAGMA enable_verification;

-- from types/list/list_case.test:8
SELECT case when 1=1 then [1] else [2] end;

-- from types/list/list_case.test:13
SELECT case when 1=0 then [1] else [2] end;

-- from types/list/list_case.test:18
SELECT case when i%2=0 then [i] else [-i] end from range(5) tbl(i);
