SELECT s[1:5] AS first_five,
       s[-5:-1] AS last_part,
       xs[2:4] AS sublist,
       xs[-1] AS last_elem
FROM (SELECT 'hello world' AS s, [10, 20, 30, 40, 50] AS xs) t;
