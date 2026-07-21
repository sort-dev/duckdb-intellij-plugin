-- from create/create_as.test:5
PRAGMA enable_verification;

-- from create/create_as.test:8
CREATE TABLE tbl1 AS SELECT 1;

-- from create/create_as.test:11
SELECT * FROM tbl1;

-- from create/create_as.test:16
CREATE TABLE tbl2 AS SELECT 2 AS f;
