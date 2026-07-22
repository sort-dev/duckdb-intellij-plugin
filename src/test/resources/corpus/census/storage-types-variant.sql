-- from storage/types/variant/append_shredded.test:6
create table tbl (col VARIANT);

-- from storage/types/variant/append_shredded.test:9
insert into tbl SELECT NULL from range(154840);

-- from storage/types/variant/append_shredded.test:12
insert into tbl SELECT True from range(5000);

-- from storage/types/variant/index_fetch.test:4
CREATE TABLE tbl(i INT PRIMARY KEY, v VARIANT);
