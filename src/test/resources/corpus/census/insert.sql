-- from insert/insert_by_name.test:5
PRAGMA enable_verification;

-- from insert/insert_by_name.test:8
CREATE TABLE integers(i INTEGER, j INTEGER);

-- from insert/insert_by_name.test:13
INSERT INTO integers BY NAME SELECT 42 AS j;

-- from insert/insert_by_name.test:17
INSERT INTO integers BY NAME SELECT 84 AS i;
