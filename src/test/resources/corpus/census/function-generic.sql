-- from function/generic/can_cast_implicitly.test:5
CREATE TABLE tbl AS SELECT * FROM range(10) tbl(i);

-- from function/generic/can_cast_implicitly.test:9
SELECT can_cast_implicitly(i, NULL::BIGINT) FROM tbl LIMIT 1;

-- from function/generic/can_cast_implicitly.test:14
SELECT can_cast_implicitly(i, NULL::HUGEINT) FROM tbl LIMIT 1;

-- from function/generic/can_cast_implicitly.test:20
SELECT can_cast_implicitly(i, NULL::INTEGER) FROM tbl LIMIT 1;
