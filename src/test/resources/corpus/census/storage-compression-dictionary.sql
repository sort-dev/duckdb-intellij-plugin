-- from storage/compression/dictionary/fetch_row.test:7
PRAGMA force_compression = 'dictionary';

-- from storage/compression/dictionary/fetch_row.test:10
CREATE TABLE test (
	a INTEGER,
	b VARCHAR
);

-- from storage/compression/dictionary/fetch_row.test:16
INSERT INTO test (a, b)
SELECT
	x AS a,
	CASE x % 5
		WHEN 0 THEN 'aaaa'
		WHEN 1 THEN 'bbbb'
		WHEN 2 THEN 'cccc'
		WHEN 3 THEN 'dddd'
		WHEN 4 THEN NULL
	END AS b
FROM range(10_000) t(x);

-- from storage/compression/dictionary/fetch_row.test:29
CHECKPOINT;
