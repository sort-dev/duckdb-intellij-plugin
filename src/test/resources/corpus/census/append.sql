-- from append/test_big_append_slow.test:5
PRAGMA enable_verification;

-- from append/test_big_append_slow.test:8
CREATE TABLE integers(i INTEGER);

-- from append/test_big_append_slow.test:13
INSERT INTO integers VALUES (1), (2), (3), (NULL);

-- from append/test_big_append_slow.test:17
INSERT INTO integers SELECT * FROM integers;
