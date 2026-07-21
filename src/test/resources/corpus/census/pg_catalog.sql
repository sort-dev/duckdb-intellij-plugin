-- from pg_catalog/pg_attribute.test:5
SELECT * FROM pg_attribute;

-- from pg_catalog/pg_attribute.test:8
SELECT * FROM pg_catalog.pg_attribute;

-- from pg_catalog/pg_attribute.test:11
CREATE TABLE integers(i integer);

-- from pg_catalog/pg_attribute.test:14
select relname, attname, attnum from pg_attribute join pg_class on (pg_attribute.attrelid=pg_class.oid) where relname='integers' and attnum>=0;
