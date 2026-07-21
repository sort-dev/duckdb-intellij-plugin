-- from table_function/duckdb_constraints_issue11284.test:5
pragma enable_verification;

-- from table_function/duckdb_constraints_issue11284.test:8
create table t (i int primary key);

-- from table_function/duckdb_constraints_issue11284.test:11
select constraint_text from duckdb_constraints() where constraint_type = 'PRIMARY KEY';

-- from table_function/duckdb_constraints_issue11284.test:16
create table u (i int references t);
