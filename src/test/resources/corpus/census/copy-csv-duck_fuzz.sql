-- from copy/csv/duck_fuzz/test_internal_4048.test:5
PRAGMA enable_verification;

-- from copy/csv/duck_fuzz/test_internal_4048.test:13
create table all_types as select * exclude(small_enum, medium_enum, large_enum) from test_all_types() limit 0;
