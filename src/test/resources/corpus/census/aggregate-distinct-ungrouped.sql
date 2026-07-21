-- from aggregate/distinct/ungrouped/test_distinct_ungrouped.test:12
PRAGMA enable_verification;

-- from aggregate/distinct/ungrouped/test_distinct_ungrouped.test:15
PRAGMA verify_external;

-- from aggregate/distinct/ungrouped/test_distinct_ungrouped.test:18
create table tbl as
	(select i%50 as i, i%100 as j from range(50000) tbl(i))
;

-- from aggregate/distinct/ungrouped/test_distinct_ungrouped.test:24
select
	count(distinct i)
from tbl;
