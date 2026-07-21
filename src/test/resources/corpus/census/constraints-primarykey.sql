-- from constraints/primarykey/test_pk_bool.test:5
CREATE TABLE integers(i INTEGER, j BOOLEAN, PRIMARY KEY(i, j));

-- from constraints/primarykey/test_pk_bool.test:8
INSERT INTO integers VALUES (1, false), (1, true), (2, false);

-- from constraints/primarykey/test_pk_bool.test:16
INSERT INTO integers VALUES (2, true);

-- from constraints/primarykey/test_pk_bool.test:19
SELECT * FROM integers ORDER BY 1, 2;
