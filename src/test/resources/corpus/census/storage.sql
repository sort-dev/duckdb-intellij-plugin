-- from storage/append_strings_to_persistent.test:8
CREATE TABLE vals(i INTEGER, v VARCHAR);

-- from storage/append_strings_to_persistent.test:11
INSERT INTO vals VALUES (1, 'hello');

-- from storage/append_strings_to_persistent.test:14
INSERT INTO vals SELECT i, i::VARCHAR FROM generate_series(2,10000) t(i);

-- from storage/append_strings_to_persistent.test:17
SELECT MIN(i), MAX(i), MIN(v), MAX(v) FROM vals;
