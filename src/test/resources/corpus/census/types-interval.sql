-- from types/interval/interval_alias.test:4
SELECT alias('5 days'::INTERVAL DAY TO SECOND);

-- from types/interval/interval_constants.test:5
PRAGMA enable_verification;

-- from types/interval/interval_constants.test:9
SELECT interval 2 days;

-- from types/interval/interval_constants.test:14
SELECT interval (2) day;
