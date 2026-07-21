-- from catalog/dependencies/add_column_to_table_referenced_by_fk.test:4
pragma enable_verification;

-- from catalog/dependencies/add_column_to_table_referenced_by_fk.test:7
create table tbl(a varchar primary key);

-- from catalog/dependencies/add_column_to_table_referenced_by_fk.test:10
create table tbl2(
	a varchar,
	foreign key (a) references tbl(a)
);

-- from catalog/dependencies/add_column_to_table_referenced_by_fk.test:16
insert into tbl values('abc');
