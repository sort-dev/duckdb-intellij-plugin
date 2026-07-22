-- from copy/csv/14512.test:5
PRAGMA enable_verification;

-- from copy/csv/14512.test:8
FROM read_csv('{DATA_DIR}/csv/14512.csv', strict_mode=TRUE);

-- from copy/csv/14512.test:13
select columns FROM sniff_csv('{DATA_DIR}/csv/14512.csv');

-- from copy/csv/14512.test:18
FROM read_csv('{DATA_DIR}/csv/14512_og.csv', strict_mode = false, delim = ',', quote = '"', escape = '"');
