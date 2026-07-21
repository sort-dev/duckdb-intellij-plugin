-- from copy/tmp_file.test:4
copy (select 42 as x) to '/tmp/duckdb_test/foo';

-- from copy/tmp_file.test:10
COPY (SELECT 36) TO '/tmp/duckdb_test/.a.b';

-- from copy/tmp_file.test:13
COPY (SELECT 37 as x) TO '/tmp/duckdb_test/.a.b';

-- from copy/tmp_file.test:16
FROM read_csv('/tmp/duckdb_test/.a.b');
