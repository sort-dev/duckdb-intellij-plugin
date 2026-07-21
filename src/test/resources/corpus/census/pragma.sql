-- from pragma/pragma_database_size_readonly.test:8
CREATE TABLE integers(i INTEGER);

-- from pragma/pragma_database_size_readonly.test:14
PRAGMA database_size;

-- from pragma/test_disabled_compression.test:7
PRAGMA disabled_compression_methods='rle';

-- from pragma/test_disabled_compression.test:7
PRAGMA disabled_compression_methods='dictionary';
