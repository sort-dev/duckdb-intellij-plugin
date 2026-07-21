-- from types/unsigned/test_unsigned_arithmetic.test:5
PRAGMA enable_verification;

-- from types/unsigned/test_unsigned_arithmetic.test:8
CREATE TABLE unsigned(a UTINYINT,b USMALLINT, c UINTEGER, d UBIGINT);

-- from types/unsigned/test_unsigned_arithmetic.test:11
INSERT INTO unsigned VALUES (1,1,1,1), (2,2,2,2);

-- from types/unsigned/test_unsigned_arithmetic.test:14
select * from unsigned;
