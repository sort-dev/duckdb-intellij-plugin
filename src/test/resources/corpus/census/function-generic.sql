-- from function/generic/can_cast_implicitly.test:5
PRAGMA enable_verification;

-- from function/generic/can_cast_implicitly.test:8
CREATE TABLE tbl AS SELECT * FROM range(10) tbl(i);

-- from function/generic/can_cast_implicitly.test:12
SELECT can_cast_implicitly(i, NULL::BIGINT) FROM tbl LIMIT 1;

-- from function/generic/can_cast_implicitly.test:17
SELECT can_cast_implicitly(i, NULL::HUGEINT) FROM tbl LIMIT 1;
