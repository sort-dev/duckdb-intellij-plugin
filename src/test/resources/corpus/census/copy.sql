-- from copy/copy_blob.test:15
COPY (select 'foo'::BLOB) TO '/tmp/duckdb_test/test.blob' (FORMAT BLOB);

-- from copy/copy_blob.test:18
select filename LIKE '%test.blob', content, size from read_blob('/tmp/duckdb_test/test.blob');

-- from copy/copy_blob.test:30
COPY (select 'foo'::BLOB) TO '/tmp/duckdb_test/test.blob.gz' (FORMAT BLOB);

-- from copy/copy_blob.test:33
select filename LIKE '%test.blob.gz', size from read_blob('/tmp/duckdb_test/test.blob.gz');
