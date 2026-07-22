-- from upsert/insert_or_replace/issue_20952_non_pk_index_column.test:5
create table t(
	id varchar primary key,
	state varchar not null,
	name varchar not null
);

-- from upsert/insert_or_replace/issue_20952_non_pk_index_column.test:12
create index idx_state on t(state);

-- from upsert/insert_or_replace/issue_20952_non_pk_index_column.test:15
insert or replace into t values ('a', 'first', 'jeremy');

-- from upsert/insert_or_replace/issue_20952_non_pk_index_column.test:18
insert or replace into t values ('a', 'second', 'tim');
