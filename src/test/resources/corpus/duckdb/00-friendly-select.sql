SELECT o.id, o.user_id, u.name, o.amount
FROM orders o
JOIN users u ON u.id = o.user_id
WHERE o.created_at >= '2026-01-01'::date
ORDER BY o.amount DESC
LIMIT 100;
