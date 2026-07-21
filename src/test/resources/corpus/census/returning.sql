-- from returning/no_crash_when_no_returning_columns.test:5
CREATE TABLE v0 ( c1 INT );

-- from returning/no_crash_when_no_returning_columns.test:14
SELECT * FROM v0;

-- from returning/no_crash_when_no_returning_columns.test:19
INSERT INTO v0 VALUES (1), (2), (3), (4), (0);

-- from returning/no_crash_when_no_returning_columns.test:28
select * from v0 where c1 = 0;
