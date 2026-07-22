-- from types/blob/test_blob.test:5
CREATE TABLE blobs (b BYTEA);

-- from types/blob/test_blob.test:9
INSERT INTO blobs VALUES('\xaa\xff\xaa'), ('\xAA\xFF\xAA\xAA\xFF\xAA'), ('\xAA\xFF\xAA\xAA\xFF\xAA\xAA\xFF\xAA');

-- from types/blob/test_blob.test:12
SELECT * FROM blobs;

-- from types/blob/test_blob.test:20
DELETE FROM blobs;
