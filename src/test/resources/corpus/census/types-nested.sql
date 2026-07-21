-- from types/nested/nested_nested_types.test:5
SELECT [{'i':1,'j':[2,3]},NULL];

-- from types/nested/nested_nested_types.test:10
SELECT [{'i':1,'j':[2,3]},NULL, {'i':1,'j':[2,3]}];

-- from types/nested/nested_nested_types.test:15
SELECT * FROM (VALUES (MAP(LIST_VALUE(1,2),LIST_VALUE(3,4))), (NULL), (MAP(LIST_VALUE(1,2),LIST_VALUE(3,4))), (NULL)) as a;

-- from types/nested/nested_nested_types.test:23
SELECT MAP(LIST_VALUE({'i':1,'j':2},{'i':3,'j':4}),LIST_VALUE({'i':1,'j':2},{'i':3,'j':4}));
