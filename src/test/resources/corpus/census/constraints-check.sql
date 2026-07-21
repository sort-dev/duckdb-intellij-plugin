-- from constraints/check/check_struct.test:5
PRAGMA enable_verification;

-- from constraints/check/check_struct.test:8
CREATE TABLE tbl(t ROW(t INTEGER) CHECK(t.t=42));

-- from constraints/check/check_struct.test:15
INSERT INTO tbl VALUES ({'t': 42});

-- from constraints/check/check_struct.test:18
DROP TABLE tbl;
