-- from copy/csv/14512.test:5
PRAGMA enable_verification;

-- from copy/csv/14512.test:8
FROM read_csv('data/csv/14512.csv', strict_mode=TRUE);

-- from copy/csv/14512.test:13
select columns FROM sniff_csv('data/csv/14512.csv');

-- from copy/csv/14512.test:18
FROM 'data/csv/14512_og.csv';
