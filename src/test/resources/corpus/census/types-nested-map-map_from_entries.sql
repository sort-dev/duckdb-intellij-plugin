-- from types/nested/map/map_from_entries/column.test:5
PRAGMA enable_verification;

-- from types/nested/map/map_from_entries/column.test:8
CREATE TABLE t1 (list STRUCT(a INT, b VARCHAR)[]);

-- from types/nested/map/map_from_entries/column.test:12
INSERT INTO t1 VALUES (ARRAY[(1, 'x'), (2, 'y'), (4, 's')]);

-- from types/nested/map/map_from_entries/column.test:15
INSERT INTO t1 VALUES (ARRAY[(2, 'a'), (3,'b')]);
