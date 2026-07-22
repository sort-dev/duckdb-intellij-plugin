-- from alter/struct/add_col_nested_struct.test:5
CREATE TABLE test(s STRUCT(s2 STRUCT(v1 INT, v2 INT)));

-- from alter/struct/add_col_nested_struct.test:8
INSERT INTO test VALUES (ROW(ROW(1, 1))), (ROW(ROW(2, 2)));

-- from alter/struct/add_col_nested_struct.test:12
ALTER TABLE test ADD COLUMN s.s2.k INTEGER;

-- from alter/struct/add_col_nested_struct.test:15
SELECT * FROM test;
