-- from storage/types/struct/struct_storage.test:8
CREATE TABLE a(b STRUCT(i INTEGER, j INTEGER));

-- from storage/types/struct/struct_storage.test:11
INSERT INTO a VALUES ({'i': 1, 'j': 2}), (NULL), ({'i': NULL, 'j': 2}), (ROW(1, NULL));

-- from storage/types/struct/struct_storage.test:14
SELECT * FROM a;

-- from storage/types/struct/struct_storage.test:22
SELECT COUNT(*) FROM a WHERE b IS NULL;
