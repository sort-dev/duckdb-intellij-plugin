-- from storage/encryption/wal/encrypted_wal_lazy_creation.test:19
ATTACH '/tmp/duckdb_test/attach_no_wal_GCM.db' AS attach_no_wal (ENCRYPTION_KEY 'asdf', ENCRYPTION_CIPHER 'GCM');

-- from storage/encryption/wal/encrypted_wal_lazy_creation.test:22
CREATE TABLE attach_no_wal.integers(i INTEGER);

-- from storage/encryption/wal/encrypted_wal_lazy_creation.test:25
INSERT INTO attach_no_wal.integers FROM range(10000);

-- from storage/encryption/wal/encrypted_wal_lazy_creation.test:28
DETACH attach_no_wal;
