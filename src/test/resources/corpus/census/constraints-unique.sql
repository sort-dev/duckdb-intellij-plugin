-- from constraints/unique/test_unique.test:5
SET default_null_order='nulls_first';

-- from constraints/unique/test_unique.test:8
CREATE TABLE integers(i INTEGER UNIQUE, j INTEGER);

-- from constraints/unique/test_unique.test:12
INSERT INTO integers VALUES (3, 4), (2, 5);

-- from constraints/unique/test_unique.test:15
SELECT * FROM integers;
