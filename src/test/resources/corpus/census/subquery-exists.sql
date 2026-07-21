-- from subquery/exists/test_correlated_exists.test:5
SET default_null_order='nulls_first';

-- from subquery/exists/test_correlated_exists.test:8
PRAGMA enable_verification;

-- from subquery/exists/test_correlated_exists.test:11
CREATE TABLE integers(i INTEGER);

-- from subquery/exists/test_correlated_exists.test:14
INSERT INTO integers VALUES (1), (2), (3), (NULL);
