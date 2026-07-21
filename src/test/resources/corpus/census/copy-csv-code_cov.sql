-- from copy/csv/code_cov/buffer_manager_finalize.test:5
PRAGMA enable_verification;

-- from copy/csv/code_cov/buffer_manager_finalize.test:8
CREATE TABLE t1 AS select i, (i+1) as j from range(0,3000) tbl(i);

-- from copy/csv/code_cov/buffer_manager_finalize.test:11
COPY t1 TO '/tmp/duckdb_test/t1.csv' (FORMAT CSV, DELIMITER '|', HEADER);

-- from copy/csv/code_cov/buffer_manager_finalize.test:14
select count(*) from '/tmp/duckdb_test/t1.csv';
