-- from function/time/epoch.test:5
PRAGMA enable_verification;

-- from function/time/epoch.test:8
select epoch(TIME '14:21:13');

-- from function/time/epoch.test:13
select extract(epoch from TIME '14:21:13');

-- from function/time/epoch.test:18
select extract(seconds from TIME '14:21:13');
