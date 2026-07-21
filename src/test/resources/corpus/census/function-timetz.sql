-- from function/timetz/test_date_part.test:5
PRAGMA enable_verification;

-- from function/timetz/test_date_part.test:8
CREATE TABLE timetzs(d TIMETZ, s VARCHAR);

-- from function/timetz/test_date_part.test:11
INSERT INTO timetzs VALUES 
	(NULL, NULL),
	('00:00:00+1559', 'timezone'),
	('00:00:00+1558', 'timezone_hour'),
	('02:30:00', 'hour'),
	('02:30:00+04', 'timezone_hour'),
	('02:30:00+04:30', 'timezone_minute'),
	('02:30:00+04:30:45', 'timezone_minute'),
	('16:15:03.123456', 'microseconds'),
	('02:30:00+1200', 'minute'),
	('02:30:00-1200', 'second'),
	('24:00:00-1558', 'timezone_hour'),
	('24:00:00-1559', 'timezone'),
	;

-- from function/timetz/test_date_part.test:28
SELECT date_part(NULL::VARCHAR, NULL::TIMETZ) FROM timetzs;
