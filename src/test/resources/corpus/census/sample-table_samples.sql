-- from sample/table_samples/test_sample_types.test:9
pragma enable_verification;

-- from sample/table_samples/test_sample_types.test:12
create table string_samples as select range::Varchar a from range(204800);

-- from sample/table_samples/test_sample_types.test:15
select count(*) from duckdb_table_sample('string_samples') where a is NULL;

-- from sample/table_samples/test_sample_types.test:20
create table struct_samples as select {'key1': 'quack-a-lack', 'key2': range} a from range(204800);
