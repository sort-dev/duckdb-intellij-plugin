-- from storage/types/test_bit_storage.test:9
CREATE TABLE bits (b BIT);

-- from storage/types/test_bit_storage.test:12
INSERT INTO bits VALUES('1'), ('010111'), ('111110010011'), (NULL), ('000000000000000000'), ('00100110010100100101001010010101010011110101000000000111100100110');

-- from storage/types/test_bit_storage.test:20
SELECT * FROM bits;

-- from storage/types/test_blob_storage.test:9
CREATE TABLE blobs (b BLOB);
