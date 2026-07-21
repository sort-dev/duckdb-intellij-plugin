-- from sample/bernoulli_sampling.test:5
create table output (num_rows INT);

-- from sample/bernoulli_sampling.test:8
select setseed(0.3);

-- from sample/bernoulli_sampling.test:13
WITH some_tab AS (
    SELECT UNNEST(range(1000)) AS id
),
some_tab_unq AS (
    SELECT distinct(id) AS id FROM some_tab
),
sampled AS (
    select id from some_tab_unq
    USING SAMPLE 1% (bernoulli)
)
INSERT INTO output select count(*) as n_rows FROM sampled;

-- from sample/bernoulli_sampling.test:29
select min(num_rows) > 0, count(*) FILTER (num_rows = 0) = 0 from output;
