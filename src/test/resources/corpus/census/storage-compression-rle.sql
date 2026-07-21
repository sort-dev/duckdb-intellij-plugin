-- from storage/compression/rle/rle_filter.test:8
pragma enable_verification;

-- from storage/compression/rle/rle_filter.test:11
PRAGMA force_compression = 'rle';

-- from storage/compression/rle/rle_filter.test:14
CREATE TABLE tbl AS SELECT i id, i // 50 rle_val, case when i%8=0 then null else i // 50 end rle_val_null FROM range(100000) t(i);

-- from storage/compression/rle/rle_filter.test:17
SELECT * FROM tbl WHERE id = 5040 AND rle_val=100;
