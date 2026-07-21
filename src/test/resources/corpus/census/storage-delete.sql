-- from storage/delete/load_delete_modify.test:8
CREATE TABLE integers AS SELECT * FROM generate_series(0,599999) t(i);

-- from storage/delete/load_delete_modify.test:11
DELETE FROM integers WHERE i%2=0;

-- from storage/delete/load_delete_modify.test:19
ALTER TABLE integers ADD COLUMN k INTEGER;

-- from storage/delete/load_delete_modify.test:22
SELECT COUNT(*), COUNT(i), COUNT(k) FROM integers;
