-- from copy/csv/multidelimiter/test_2_byte_delimiter.test:5
PRAGMA enable_verification;

-- from copy/csv/multidelimiter/test_2_byte_delimiter.test:9
FROM read_csv('data/csv/multidelimiter/aa_delim_small.csv', delim = 'aa', header = False,  buffer_size = 8);

-- from copy/csv/multidelimiter/test_2_byte_delimiter.test:19
FROM read_csv('data/csv/multidelimiter/ab_delim.csv', delim = 'ab', header = False, buffer_size = 9);

-- from copy/csv/multidelimiter/test_2_byte_delimiter.test:42
FROM read_csv('data/csv/multidelimiter/aa_delim.csv', delim = 'aa', header = False,  buffer_size = 9);
