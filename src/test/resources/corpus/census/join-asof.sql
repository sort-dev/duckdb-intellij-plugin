-- from join/asof/test_asof_join.test:5
PRAGMA enable_verification;

-- from join/asof/test_asof_join.test:9
CREATE TABLE events0 (begin DOUBLE, value INTEGER);

-- from join/asof/test_asof_join.test:12
INSERT INTO events0 VALUES
	(1, 0),
	(3, 1),
	(6, 2),
	(8, 3)
;

-- from join/asof/test_asof_join.test:21
create table prices("when" timestamp, symbol int, price int);
