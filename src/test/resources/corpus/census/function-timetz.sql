-- from function/timetz/test_date_part.test:5
PRAGMA enable_verification;

-- from function/timetz/test_date_part.test:8
CREATE TABLE timetzs(d TIMETZ, s VARCHAR);

-- from function/timetz/test_date_part.test:28
SELECT date_part(NULL::VARCHAR, NULL::TIMETZ) FROM timetzs;

-- from function/timetz/test_date_part.test:44
SELECT date_part(s, NULL::TIMETZ) FROM timetzs;
