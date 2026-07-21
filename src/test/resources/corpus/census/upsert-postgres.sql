-- from upsert/postgres/composite_key.test:4
pragma enable_verification;

-- from upsert/postgres/composite_key.test:9
create table insertconflicttest(
	key int4,
	fruit text,
	other int4,
	unique (key, fruit)
);

-- from upsert/postgres/composite_key.test:29
insert into insertconflicttest values(0, 'Crowberry', 0) on conflict (key, fruit) do nothing;

-- from upsert/postgres/composite_key.test:32
insert into insertconflicttest values(0, 'Crowberry', 0) on conflict (fruit, key, fruit, key) do nothing;
