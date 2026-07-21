-- from upsert/insert_or_replace/pk_and_non_unique_index.test:5
create table tbl(
	a int,
	b int,
	c int,
	primary key(a,b,c)
);

-- from upsert/insert_or_replace/pk_and_non_unique_index.test:13
create index non_unique on tbl(b);

-- from upsert/insert_or_replace/pk_and_non_unique_index.test:16
insert or replace into tbl values (1,2,3);

-- from upsert/insert_or_replace/pk_and_non_unique_index.test:22
select * from tbl;
