-- from types/union/struct_to_union.test:4
create table union_tbl(
	col UNION(
		a BOOL,
		b INTEGER,
		c TINYINT
	)
);

-- from types/union/struct_to_union.test:42
create table struct_tbl(
	col STRUCT(
		tag UINT8,
		A BOOL,
		B INTEGER,
		C TINYINT
	)
);

-- from types/union/struct_to_union.test:52
INSERT INTO struct_tbl VALUES
	(ROW(0, True, NULL, NULL)),
	(ROW(1, NULL, 23423, NULL)),
    (ROW(0, True, NULL, NULL));

-- from types/union/struct_to_union.test:59
insert into union_tbl select * from struct_tbl;
