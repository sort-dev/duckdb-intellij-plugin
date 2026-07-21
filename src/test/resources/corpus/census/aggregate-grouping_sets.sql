-- from aggregate/grouping_sets/cube.test:5
SET default_null_order='nulls_first';

-- from aggregate/grouping_sets/cube.test:8
PRAGMA enable_verification;

-- from aggregate/grouping_sets/cube.test:11
create table students (course VARCHAR, type VARCHAR, highest_grade INTEGER);

-- from aggregate/grouping_sets/cube.test:14
insert into students
		(course, type, highest_grade)
	values
		('CS', 'Bachelor', 8),
		('CS', 'Bachelor', 8),
		('CS', 'PhD', 10),
		('Math', 'Masters', NULL),
		('CS', NULL, 7),
		('CS', NULL, 7),
		('Math', NULL, 8);
