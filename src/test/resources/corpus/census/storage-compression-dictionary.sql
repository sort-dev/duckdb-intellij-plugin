-- from storage/compression/dictionary/dictionary_storage_info.test:8
PRAGMA force_compression = 'dictionary';

-- from storage/compression/dictionary/dictionary_storage_info.test:11
CREATE TABLE test (a VARCHAR, b VARCHAR);

-- from storage/compression/dictionary/dictionary_storage_info.test:14
INSERT INTO test VALUES ('11', '22'), ('11', '22'), ('12', '21'), (NULL, NULL);

-- from storage/compression/dictionary/dictionary_storage_info.test:17
CHECKPOINT;
