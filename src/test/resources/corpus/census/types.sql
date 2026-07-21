-- from types/test_date_cast.test:5
CREATE TABLE df (x VARCHAR, y BIGINT);

-- from types/test_date_cast.test:8
INSERT INTO df VALUES ('2021-01-01 12:00:00', 1);

-- from types/test_date_cast.test:11
select
	CAST(x as DATE) = '2021-01-01' a,
	IF(CAST(x as DATE) = '2021-01-01', y, 0) b,
	CASE WHEN CAST(x as DATE) = '2021-01-01' THEN y ELSE 0 END c,
	IF(CAST(x as DATE) = '2021-01-01', 1, 0) d
from df;

-- from types/test_typeof.test:4
PRAGMA enable_verification;
