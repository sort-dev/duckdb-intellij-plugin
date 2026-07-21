SELECT user_id, count(*) AS n
FROM orders
GROUP BY ALL
QUALIFY row_number() OVER (ORDER BY n DESC) <= 10;
