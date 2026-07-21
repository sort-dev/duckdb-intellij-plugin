-- from types/null/test_boolean_null.test:5
SET default_null_order='nulls_first';

-- from types/null/test_boolean_null.test:8
PRAGMA enable_verification;

-- from types/null/test_boolean_null.test:12
SELECT 0 AND 0, 0 AND 1, 1 AND 0, 1 AND 1, NULL AND 0, NULL AND 1, 0 AND NULL, 1 AND NULL, NULL AND NULL;

-- from types/null/test_boolean_null.test:18
SELECT 0 OR 0, 0 OR 1, 1 OR 0, 1 OR 1, NULL OR 0, NULL OR 1, 0 OR NULL, 1 OR NULL, NULL OR NULL;
