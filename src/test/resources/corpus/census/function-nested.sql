-- from function/nested/array_extract_unnamed_struct.test:10
SELECT (ROW(42, 84))[1];

-- from function/nested/array_extract_unnamed_struct.test:15
SELECT (ROW(42, 84))[2];

-- from function/nested/array_extract_unnamed_struct.test:20
SELECT UNNEST(ROW(42, 84));

-- from function/nested/test_issue_5437.test:5
with data as (
select * from (VALUES ('Amsterdam', {'x': 1, 'y': 2, 'z': 3}), ('London', {'x': 4, 'y': 5, 'z': 6})) Cities(Name, Id)
)
select *, struct_insert(Id, d := 4)
from data;
