FROM orders
SELECT user_id, sum(amount) AS total
GROUP BY user_id;

PIVOT sales ON year USING sum(amount) GROUP BY region;
