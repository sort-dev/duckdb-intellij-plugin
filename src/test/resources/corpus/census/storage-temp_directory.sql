-- from storage/temp_directory/temp_directory_null.test:5
set temp_directory='';

-- from storage/temp_directory/temp_directory_null.test:8
select value from duckdb_settings() where name = 'temp_directory';

-- from storage/temp_directory/temp_directory_null.test:14
set temp_directory=null;

-- from storage/temp_directory/test_default_temp_directory.test:6
SET memory_limit='2MB';
