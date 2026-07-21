SELECT [x * 2 FOR x IN xs] AS doubled,
       [x FOR x IN xs IF x > 1] AS filtered
FROM (SELECT [1, 2, 3, 4] AS xs) t;
