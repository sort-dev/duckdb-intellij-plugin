SELECT user_id, amount FROM orders ORDER BY ALL;

SELECT amount FROM orders ORDER BY ALL DESC;

SELECT COLUMNS('.*_id') FROM orders;
