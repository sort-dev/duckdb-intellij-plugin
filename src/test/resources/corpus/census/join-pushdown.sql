-- from join/pushdown/pushdown_generated_columns.test:5
PRAGMA enable_verification;

-- from join/pushdown/pushdown_generated_columns.test:8
CREATE TABLE unit2(
	price INTEGER,
	amount_sold INTEGER,
	total_profit INTEGER GENERATED ALWAYS AS (price * amount_sold) VIRTUAL,
	also_total_profit INTEGER GENERATED ALWAYS AS (total_profit) VIRTUAL
);

-- from join/pushdown/pushdown_generated_columns.test:16
INSERT INTO unit2 SELECT i, 20 FROM range(1000) t(i);

-- from join/pushdown/pushdown_generated_columns.test:19
SELECT * FROM unit2 JOIN (VALUES (2000)) t(total_profit) USING (total_profit);
