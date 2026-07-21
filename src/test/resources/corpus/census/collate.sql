-- from collate/collate_filter_pushdown.test:5
PRAGMA enable_verification;

-- from collate/collate_filter_pushdown.test:8
CREATE TABLE t0(c0 BOOLEAN, PRIMARY KEY(c0));

-- from collate/collate_filter_pushdown.test:11
CREATE TABLE t63(c0 VARCHAR COLLATE C, PRIMARY KEY(c0));

-- from collate/collate_filter_pushdown.test:14
insert into t0(c0) values (0.7);
