-- from function/array/array_and_map.test:5
SELECT MAP([MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2])], [1]);

-- from function/array/array_and_map.test:10
SELECT MAP([2], [{'key1': MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2])}]);

-- from function/array/array_and_map.test:17
SELECT [MAP([2], [{'key1': MAP([ARRAY_VALUE('1', NULL), ARRAY_VALUE(NULL, '2')], [1, 2]), 'key2': 2}])];

-- from function/array/array_cosine_distance.test:7
SELECT array_cosine_distance([1, 2, 3]::FLOAT[3], [1, 2, 3]::FLOAT[3]);
