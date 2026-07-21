-- from storage/wal/wal_blob_storage.test:8
PRAGMA disable_checkpoint_on_shutdown;

-- from storage/wal/wal_blob_storage.test:11
PRAGMA wal_autocheckpoint='1TB';

-- from storage/wal/wal_blob_storage.test:15
CREATE TABLE blobs (b BLOB);

-- from storage/wal/wal_blob_storage.test:18
INSERT INTO blobs VALUES('a'), ('\xAA'), ('\xAA\xFF\xAA'),  (''), (NULL), ('\x55\xAA\xFF\x55\xAA\xFF\x55\xAA\xFF\x01'), ('\x55\xAA\xFF\x55\xAA\xFF\x55\xAA\xFF\x01');
