-- from aggregate/aggregates/approx_top_k.test:5
PRAGMA enable_verification;

-- from aggregate/aggregates/approx_top_k.test:8
CREATE TABLE integers AS SELECT i%5 as even_groups, log(1 + i*i)::int as skewed_groups  FROM range(10000) t(i);

-- from aggregate/aggregates/approx_top_k.test:12
SELECT list_sort(approx_top_k(even_groups, 10)) FROM integers;

-- from aggregate/aggregates/approx_top_k.test:19
SELECT approx_top_k(skewed_groups, 5) FROM integers;
