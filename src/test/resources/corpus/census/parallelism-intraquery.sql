-- from parallelism/intraquery/error_in_pipeline.test:5
PRAGMA enable_verification;

-- from parallelism/intraquery/error_in_pipeline.test:8
PRAGMA threads=16;

-- from parallelism/intraquery/error_in_pipeline.test:11
create table varchars as select i::varchar i from range(1000000) tbl(i);

-- from parallelism/intraquery/error_in_pipeline.test:14
insert into varchars values ('hello');
