-- from storage/memory/in_memory_compress.test:5
PRAGMA enable_verification;

-- from storage/memory/in_memory_compress.test:8
ATTACH ':memory:' AS memory_compressed (COMPRESS);

-- from storage/memory/in_memory_compress.test:11
CREATE TABLE memory_compressed.a(i INTEGER);

-- from storage/memory/in_memory_compress.test:14
INSERT INTO memory_compressed.a FROM range(10000000);
