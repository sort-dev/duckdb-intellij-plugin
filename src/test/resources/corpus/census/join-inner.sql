-- from join/inner/empty_tinyint_column.test:5
PRAGMA enable_verification;

-- from join/inner/empty_tinyint_column.test:8
CREATE TABLE t1(c0 INT4, c1 VARCHAR);

-- from join/inner/empty_tinyint_column.test:11
CREATE TABLE t2(c0 TINYINT, PRIMARY KEY(c0));

-- from join/inner/empty_tinyint_column.test:14
INSERT INTO t1(c0) VALUES (14161972);
