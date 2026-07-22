-- from copy/csv/parallel/csv_parallel_buffer_size.test:6
PRAGMA verify_parallelism;

-- from copy/csv/parallel/csv_parallel_buffer_size.test:9
SELECT sum(a), sum(b), sum(c) FROM read_csv('{DATA_DIR}/csv/test/multi_column_integer.csv',  COLUMNS=STRUCT_PACK(a := 'INTEGER', b := 'INTEGER', c := 'INTEGER'), auto_detect='true', delim = '|', buffer_size=30);

-- from copy/csv/parallel/csv_parallel_buffer_size.test:14
SELECT sum(a) FROM read_csv('{DATA_DIR}/csv/test/multi_column_integer.csv',  COLUMNS=STRUCT_PACK(a := 'INTEGER', b := 'INTEGER', c := 'INTEGER'), auto_detect='true', delim = '|', buffer_size=30);

-- from copy/csv/parallel/csv_parallel_buffer_size.test:19
SELECT sum(a) FROM read_csv('{DATA_DIR}/csv/test/multi_column_integer_rn.csv',  COLUMNS=STRUCT_PACK(a := 'INTEGER', b := 'INTEGER', c := 'INTEGER'), auto_detect='true', delim = '|', buffer_size=30);
