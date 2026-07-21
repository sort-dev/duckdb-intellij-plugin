-- from types/time/test_time.test:5
PRAGMA enable_verification;

-- from types/time/test_time.test:8
CREATE TABLE times(i TIME);

-- from types/time/test_time.test:11
INSERT INTO times VALUES ('00:01:20'), ('20:08:10.998'), ('20:08:10.33'), ('20:08:10.001'), (NULL);

-- from types/time/test_time.test:14
SELECT * FROM times;
