WITH ranked AS (
    SELECT user_id, amount,
           row_number() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn,
           sum(amount) OVER (PARTITION BY user_id) AS total
    FROM orders
)
SELECT user_id, amount, total FROM ranked WHERE rn <= 3;
