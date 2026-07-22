-- from alter/map/add_column_in_struct.test:4
WITH cte as (
	select a::MAP(STRUCT(n INTEGER, m INTEGER), STRUCT(i INTEGER, j INTEGER)) a from
	VALUES
		(MAP {ROW(3,3): ROW(1, 1)}),
		(MAP {ROW(4,4): ROW(2, 2)})
	t(a)
)
SELECT remap_struct(
	a,
	NULL::MAP(STRUCT(n INTEGER, m INTEGER), STRUCT(i INTEGER, j INTEGER, k INTEGER)),
	{
		'key': 'key',
		'value': (
			'value', {
				'i': 'i',
				'j': 'j'
			}
		)
	},
	{
		'value': {
			'k': NULL::INTEGER
		}
	}
) from cte;

-- from alter/map/add_column_in_struct.test:35
CREATE TABLE test(
	s MAP(
		STRUCT(
			n INTEGER,
			m INTEGER
		),
		STRUCT(
			i INTEGER,
			j INTEGER
		)
	)
);

-- from alter/map/add_column_in_struct.test:49
INSERT INTO test VALUES
	(MAP {ROW(3,3): ROW(1, 1)}),
	(MAP {ROW(4,4): ROW(2, 2)});

-- from alter/map/add_column_in_struct.test:55
ALTER TABLE test ADD COLUMN s.key.k INTEGER;
