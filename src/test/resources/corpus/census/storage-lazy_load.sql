-- from storage/lazy_load/lazy_load_limit.test:8
CREATE TABLE vals(i INTEGER, v VARCHAR);

-- from storage/lazy_load/lazy_load_limit.test:11
INSERT INTO vals SELECT i, i::VARCHAR FROM generate_series(1000000) t(i);

-- from storage/lazy_load/lazy_load_limit.test:18
SELECT COUNT(*)=(0*100000) FROM (FROM vals LIMIT 0*100000);

-- from storage/lazy_load/lazy_load_limit.test:18
SELECT COUNT(*)=(1*100000) FROM (FROM vals LIMIT 1*100000);
