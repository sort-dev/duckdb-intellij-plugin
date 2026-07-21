-- from storage/compression/zstd/fetch_row.test:4
SET storage_compatibility_version='v1.2.0';

-- from storage/compression/zstd/fetch_row.test:10
CREATE TABLE big_string (
	a VARCHAR,
	id INT
);

-- from storage/compression/zstd/fetch_row.test:16
pragma force_compression='zstd';

-- from storage/compression/zstd/fetch_row.test:19
INSERT INTO big_string values (repeat('a', 8000), 1);
INSERT INTO big_string values (repeat('b', 10), 2);
INSERT INTO big_string values (repeat('c', 8000), 3);
INSERT INTO big_string values (repeat('d', 10), 4);
INSERT INTO big_string values (repeat('a', 8000), 1);
INSERT INTO big_string values (repeat('b', 10), 2);
INSERT INTO big_string values (repeat('c', 8000), 3);
INSERT INTO big_string values (repeat('d', 10), 4);
INSERT INTO big_string values (repeat('a', 8000), 1);
INSERT INTO big_string values (repeat('b', 10), 2);
INSERT INTO big_string values (repeat('c', 8000), 3);
INSERT INTO big_string values (repeat('d', 10), 4);
