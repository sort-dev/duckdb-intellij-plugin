-- from function/timestamp/age.test:6
SELECT AGE(TIMESTAMP '1957-06-13') t;

-- from function/timestamp/age.test:9
SELECT AGE(TIMESTAMP '2001-04-10', TIMESTAMP '1957-06-13');

-- from function/timestamp/age.test:14
SELECT age(TIMESTAMP '2014-04-25', TIMESTAMP '2014-04-17');

-- from function/timestamp/age.test:19
SELECT age(TIMESTAMP '2014-04-25', TIMESTAMP '2014-01-01');
