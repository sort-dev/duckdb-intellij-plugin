-- from storage/compression/fsst/fsst_disable_compression.test:8
CREATE TABLE test AS SELECT concat('longprefix', i) FROM range(30000) t(i);

-- from storage/compression/fsst/fsst_disable_compression.test:11
CHECKPOINT;

-- from storage/compression/fsst/fsst_disable_compression.test:14
SELECT BOOL_OR(compression ILIKE '%fsst%') FROM pragma_storage_info('test');

-- from storage/compression/fsst/fsst_disable_compression.test:19
DROP TABLE test;
