-- from collate/collate_filter_pushdown.test:5
CREATE TABLE t0(c0 BOOLEAN, PRIMARY KEY(c0));

-- from collate/collate_filter_pushdown.test:8
CREATE TABLE t63(c0 VARCHAR COLLATE C, PRIMARY KEY(c0));

-- from collate/collate_filter_pushdown.test:11
insert into t0(c0) values (0.7);

-- from collate/collate_filter_pushdown.test:14
insert into t63(c0) values ('1');
