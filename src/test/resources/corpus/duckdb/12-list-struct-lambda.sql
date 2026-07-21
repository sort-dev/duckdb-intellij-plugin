SELECT [1, 2, 3] AS xs,
       {'a': 1, 'b': 'two'} AS st,
       list_transform([1, 2, 3], x -> x * 2) AS doubled,
       xs[1] AS first_elem;
