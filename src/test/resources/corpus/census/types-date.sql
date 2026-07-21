-- from types/date/date_implicit_cast.test:5
PRAGMA enable_verification;

-- from types/date/date_implicit_cast.test:10
CREATE TABLE timestamps(ts timestamp);

-- from types/date/date_implicit_cast.test:13
INSERT INTO timestamps VALUES ('1993-08-14 00:00:00'), ('1993-08-15 01:01:02'), ('1993-08-16 00:00:00');

-- from types/date/date_implicit_cast.test:16
SELECT * FROM timestamps WHERE ts >= date '1993-08-15';
