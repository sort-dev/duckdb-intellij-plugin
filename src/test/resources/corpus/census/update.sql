-- from update/null_update_merge.test:6
CREATE TABLE test (id INTEGER, a INTEGER);

-- from update/null_update_merge.test:9
INSERT INTO test VALUES (1, 1), (2, 2), (3, 3), (4, NULL);

-- from update/null_update_merge.test:12
SELECT * FROM test ORDER BY id;

-- from update/null_update_merge.test:20
UPDATE test SET a=CASE WHEN id=1 THEN 7 ELSE NULL END WHERE id <= 2;
