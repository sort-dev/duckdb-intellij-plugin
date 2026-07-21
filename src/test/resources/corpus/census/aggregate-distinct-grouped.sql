-- from aggregate/distinct/grouped/combined_with_grouping.test:4
SET default_null_order='nulls_first';

-- from aggregate/distinct/grouped/combined_with_grouping.test:7
PRAGMA enable_verification;

-- from aggregate/distinct/grouped/combined_with_grouping.test:10
PRAGMA verify_parallelism;

-- from aggregate/distinct/grouped/combined_with_grouping.test:13
create table students (
	course VARCHAR,
	type VARCHAR,
	value BIGINT
);
