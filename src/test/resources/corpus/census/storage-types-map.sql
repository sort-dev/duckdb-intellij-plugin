-- from storage/types/map/map_storage.test:8
CREATE TABLE a(b MAP(INTEGER,INTEGER));

-- from storage/types/map/map_storage.test:11
INSERT INTO a VALUES (MAP([1], [2])), (MAP([1, 2, 3], [4, 5, 6]));

-- from storage/types/map/map_storage.test:14
SELECT * FROM a;
