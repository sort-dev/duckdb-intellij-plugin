-- from storage/compression/roaring/roaring_array_simple.test:7
PRAGMA force_compression='roaring';

-- from storage/compression/roaring/roaring_array_simple.test:11
CREATE TABLE test (a BIGINT);

-- from storage/compression/roaring/roaring_array_simple.test:15
INSERT INTO test SELECT case when i%25=0 then 1337 else null end FROM range(0,10000) tbl(i);

-- from storage/compression/roaring/roaring_array_simple.test:18
checkpoint;
