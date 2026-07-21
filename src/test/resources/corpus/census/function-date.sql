-- from function/date/date_add.test:5
PRAGMA enable_verification;

-- from function/date/date_add.test:8
CREATE TABLE dates(d DATE);

-- from function/date/date_add.test:11
INSERT INTO dates VALUES (DATE '1992-01-01');

-- from function/date/date_add.test:15
SELECT DATE_ADD(DATE '2008-12-25', INTERVAL 5 DAY) AS five_days_later;
