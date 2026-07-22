-- from show_select/describe_rowid.test:4
create table sometable (
	column1 varchar
);

-- from show_select/describe_rowid.test:9
insert into sometable values
	('abc');

-- from show_select/describe_rowid.test:13
create view someview as select
	rowid as table_rowid,
	*
from sometable;

-- from show_select/describe_rowid.test:19
select * from someview;
