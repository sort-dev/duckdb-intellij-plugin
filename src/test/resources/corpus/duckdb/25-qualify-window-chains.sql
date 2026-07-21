SELECT user_id, amount,
       row_number() OVER w AS rn,
       sum(amount) OVER w AS running
FROM orders
WINDOW w AS (PARTITION BY user_id ORDER BY amount DESC)
QUALIFY rn <= 3;
