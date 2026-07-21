-- from copy/csv/auto/test_14177.test:5
PRAGMA enable_verification;

-- from copy/csv/auto/test_14177.test:8
select count(*) FROM (FROM read_csv('data/csv/auto/14177.csv', buffer_size=80, ignore_errors = true)) as t;

-- from copy/csv/auto/test_auto_5250.test:8
PRAGMA verify_parallelism;

-- from copy/csv/auto/test_auto_5250.test:11
select count(*) from read_csv_auto('data/csv/page_namespacepage_title_sample.csv', SAMPLE_SIZE = -1);
