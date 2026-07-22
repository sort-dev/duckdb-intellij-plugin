-- from function/operator/test_arithmetic.test:5
SET default_null_order='nulls_first';

-- from function/operator/test_arithmetic.test:8
CREATE TABLE integers(i INTEGER);

-- from function/operator/test_arithmetic.test:11
INSERT INTO integers VALUES (1), (2), (3), (NULL);

-- from function/operator/test_arithmetic.test:19
SELECT i+2=5, 5=i+2 FROM integers ORDER BY i;
