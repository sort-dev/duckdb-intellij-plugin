-- from constraints/check/check_struct.test:5
CREATE TABLE tbl(t ROW(t INTEGER) CHECK(t.t=42));

-- from constraints/check/check_struct.test:12
INSERT INTO tbl VALUES ({'t': 42});

-- from constraints/check/check_struct.test:15
DROP TABLE tbl;

-- from constraints/check/check_struct.test:19
CREATE TABLE tbl(t ROW(t INTEGER) CHECK(tbl.t.t=42));
