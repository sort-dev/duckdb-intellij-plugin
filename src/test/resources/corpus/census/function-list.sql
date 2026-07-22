-- from function/list/array_length.test:5
SELECT length([1,2,3]);

-- from function/list/array_length.test:10
SELECT length([]);

-- from function/list/array_length.test:15
SELECT len(NULL);

-- from function/list/array_length.test:20
SELECT array_length(ARRAY[1, 2, 3], 1);
