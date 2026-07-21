-- from function/operator/test_arithmetic.test:5
SET default_null_order='nulls_first';

-- from function/operator/test_arithmetic.test:8
PRAGMA enable_verification;

-- from function/operator/test_arithmetic.test:11
CREATE TABLE integers(i INTEGER);

-- from function/operator/test_arithmetic.test:14
INSERT INTO integers VALUES (1), (2), (3), (NULL);
