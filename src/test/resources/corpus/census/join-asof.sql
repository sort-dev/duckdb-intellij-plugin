-- from join/asof/test_asof_empty_right.test:5
PRAGMA enable_verification;

-- from join/asof/test_asof_empty_right.test:8
select lefttable.x, righttable.y
from (select 1 as x) lefttable
asof left join (select 1 as x, 1 as y limit 0) righttable
on lefttable.x >= righttable.x;

-- from join/asof/test_asof_empty_right.test:16
select lefttable.x, righttable.y
from (select 1 as x limit 0) lefttable
asof left join (select 1 as x, 1 as y) righttable
on lefttable.x >= righttable.x;

-- from join/asof/test_asof_empty_right.test:23
select lefttable.x, righttable.y
from (select 1 as x) lefttable
asof join (select 1 as x, 1 as y limit 0) righttable
on lefttable.x >= righttable.x;
