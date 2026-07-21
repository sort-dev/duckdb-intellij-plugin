-- from function/array/array_and_map.test:5
PRAGMA enable_verification;

-- from function/array/array_and_map.test:8
SELECT MAP([MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2])], [1]);

-- from function/array/array_and_map.test:13
SELECT MAP([2], [{'key1': MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2])}]);

-- from function/array/array_and_map.test:20
SELECT [MAP([2], [{'key1': MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2]), 'key2': 2}])];
