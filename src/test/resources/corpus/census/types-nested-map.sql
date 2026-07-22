-- from types/nested/map/map_error.test:25
CREATE TABLE tbl (a INTEGER[], b TEXT[]);

-- from types/nested/map/map_error.test:28
INSERT INTO tbl VALUES (ARRAY[7, 5, 7], ARRAY['a', 'b', 'c']);

-- from types/nested/map/map_error.test:61
CREATE TABLE t AS SELECT MAP(list_value(1, 2, 3), list_value(10, 9, 10)) AS m;

-- from types/nested/map/map_error.test:69
CREATE TABLE null_keys_list (k INT[], v INT[]);
