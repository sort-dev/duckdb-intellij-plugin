-- from settings/default_null_order_extended.test:5
PRAGMA enable_verification;

-- from settings/default_null_order_extended.test:8
CREATE TABLE integers(i integer);

-- from settings/default_null_order_extended.test:11
INSERT INTO integers VALUES (1), (2), (3), (NULL);

-- from settings/default_null_order_extended.test:15
SET default_null_order='nulls_first';
