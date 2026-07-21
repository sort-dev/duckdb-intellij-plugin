-- from types/map/map_cast.test:5
PRAGMA enable_verification;

-- from types/map/map_cast.test:8
SELECT MAP(['a', 'b', 'c'], [1, 2, NULL])::MAP(VARCHAR, VARCHAR);

-- from types/map/map_cast.test:13
SELECT MAP(['a', 'b', 'c'], [1, 2, NULL])::MAP(VARCHAR, BIGINT);

-- from types/map/map_cast.test:19
SELECT MAP([1, 2, 3], [1, 2, NULL])::MAP(VARCHAR, BIGINT);
