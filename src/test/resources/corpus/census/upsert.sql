-- from upsert/minimal_reproducable_example.test:4
pragma enable_verification;

-- from upsert/minimal_reproducable_example.test:8
create or replace table tbl(
	i integer PRIMARY KEY,
	j integer UNIQUE,
	k integer
);

-- from upsert/minimal_reproducable_example.test:16
insert into tbl VALUES (1, 10, 1), (2, 20, 1), (3, 30, 2);

-- from upsert/minimal_reproducable_example.test:19
select * from tbl;
