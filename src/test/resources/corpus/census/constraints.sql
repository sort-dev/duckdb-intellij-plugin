-- from constraints/test_constraint_with_updates.test:5
CREATE TABLE integers(i INTEGER, j INTEGER CHECK(i + j < 5), k INTEGER);

-- from constraints/test_constraint_with_updates.test:8
INSERT INTO integers VALUES (1, 2, 4);

-- from constraints/test_constraint_with_updates.test:12
UPDATE integers SET k=7;

-- from constraints/test_constraint_with_updates.test:16
UPDATE integers SET i=i, j=3;
