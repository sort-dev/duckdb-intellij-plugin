-- from projection/coalesce_error.test:5
PRAGMA enable_verification;

-- from projection/coalesce_error.test:9
SELECT COALESCE(1, 'hello'::INT);

-- from projection/coalesce_error.test:20
CREATE TABLE vals AS SELECT * FROM (
	VALUES (1, 'hello'), (NULL, '2'), (3, NULL)
) tbl(a, b);

-- from projection/coalesce_error.test:25
SELECT COALESCE(a, b::INT) FROM vals;
