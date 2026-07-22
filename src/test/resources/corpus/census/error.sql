-- from error/aggregate_order_by.test:5
CREATE TABLE lists_tbl AS SELECT i%20 as groups, i AS l FROM range(1000) tmp(i);

-- from error/correlated_at_clause.test:5
CREATE TABLE t (i VARCHAR);

-- from error/error_position.test:5
set errors_as_json=true;

-- from error/escape_percent_sign.test:5
CREATE VIEW list_int AS
SELECT case when i%2 <> 0 then [1] else NULL end FROM range(10000) tbl(i);
