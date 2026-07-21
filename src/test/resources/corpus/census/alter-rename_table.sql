-- from alter/rename_table/test_rename_bug4455_schema.test:5
create schema public;

-- from alter/rename_table/test_rename_bug4455_schema.test:8
set schema=public;

-- from alter/rename_table/test_rename_bug4455_schema.test:11
create table a1 (c int);

-- from alter/rename_table/test_rename_bug4455_schema.test:14
alter table public.a1 rename to a2;
