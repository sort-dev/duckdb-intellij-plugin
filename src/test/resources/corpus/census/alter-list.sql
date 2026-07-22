-- from alter/list/add_column_in_struct.test:5
WITH cte AS (
	SELECT a::STRUCT(i INTEGER, j INTEGER)[] a FROM
	VALUES ([ROW(1, 1)]), ([ROW(2, 2)]) t(a)
)
SELECT remap_struct(
	a,
	NULL::STRUCT(i INTEGER, j INTEGER, k INTEGER)[],
	{'list': ('list', {'i': 'i', 'j': 'j'})},
	{'list': {'k': NULL::INTEGER}}
) FROM cte;

-- from alter/list/add_column_in_struct.test:21
CREATE TABLE test(s STRUCT(i INTEGER, j INTEGER)[]);

-- from alter/list/add_column_in_struct.test:24
INSERT INTO test VALUES ([ROW(1, 1)]), ([ROW(2, 2)]);

-- from alter/list/add_column_in_struct.test:29
ALTER TABLE test ADD COLUMN s.element.k INTEGER;
