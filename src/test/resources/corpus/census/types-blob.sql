-- from types/blob/test_blob.test:5
PRAGMA enable_verification;

-- from types/blob/test_blob.test:8
CREATE TABLE blobs (b BYTEA);

-- from types/blob/test_blob.test:12
INSERT INTO blobs VALUES('\xaa\xff\xaa'), ('\xAA\xFF\xAA\xAA\xFF\xAA'), ('\xAA\xFF\xAA\xAA\xFF\xAA\xAA\xFF\xAA');

-- from types/blob/test_blob.test:15
SELECT * FROM blobs;
