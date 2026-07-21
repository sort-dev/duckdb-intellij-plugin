-- from catalog/table/create_table_as_abort.test:5
CREATE TABLE integers(i INTEGER);

-- from catalog/table/create_table_as_abort.test:8
CREATE TABLE IF NOT EXISTS integers AS SELECT i1.i FROM range(10000000000000000) i1(i);

-- from catalog/table/long_identifier.test:5
create table integers("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" integer);

-- from catalog/table/long_identifier.test:8
select "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" from integers;
