-- from storage/alter/alter_column_constraint.test:8
CREATE TABLE IF NOT EXISTS a(id INT PRIMARY KEY);

-- from storage/alter/alter_column_constraint.test:11
INSERT INTO a(id) VALUES (1);

-- from storage/alter/alter_column_constraint.test:15
ALTER TABLE a ADD COLUMN c REAL;

-- from storage/alter/alter_column_constraint.test:29
ALTER TABLE a ALTER COLUMN c SET DEFAULT 10;
