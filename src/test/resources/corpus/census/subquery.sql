-- from subquery/test_neumann.test:5
PRAGMA enable_verification;

-- from subquery/test_neumann.test:8
CREATE TABLE students(id INTEGER, name VARCHAR, major VARCHAR, year INTEGER);

-- from subquery/test_neumann.test:11
CREATE TABLE exams(sid INTEGER, course VARCHAR, curriculum VARCHAR, grade INTEGER, year INTEGER);

-- from subquery/test_neumann.test:14
INSERT INTO students VALUES (1, 'Mark', 'CS', 2017);
