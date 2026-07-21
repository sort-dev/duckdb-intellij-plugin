-- from copy/csv/afl/fuzz_20250211_crash.test:5
PRAGMA enable_verification;

-- from copy/csv/afl/test_fuzz_3977.test:7
select count(file) from glob('./data/csv/afl/3977/*');
