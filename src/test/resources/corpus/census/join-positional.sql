-- from join/positional/test_positional_join.test:5
PRAGMA enable_verification;

-- from join/positional/test_positional_join.test:8
CREATE TABLE two (a INTEGER, b INTEGER);

-- from join/positional/test_positional_join.test:11
INSERT INTO two VALUES (11, 1), (12, 2);

-- from join/positional/test_positional_join.test:14
CREATE TABLE three AS 
	SELECT * FROM (VALUES
		(11, 1),
		(12, 2),
		(13, 3)
	) tbl(a, b);
