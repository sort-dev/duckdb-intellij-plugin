-- from storage/compression/rle/rle_bool.test:8
PRAGMA force_compression = 'rle';

-- from storage/compression/rle/rle_bool.test:12
CREATE TABLE test (a BOOLEAN);

-- from storage/compression/rle/rle_bool.test:15
INSERT INTO test select false from range(2048);

-- from storage/compression/rle/rle_bool.test:18
INSERT INTO test select true from range(2048);
