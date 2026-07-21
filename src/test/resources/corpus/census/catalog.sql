-- from catalog/case_insensitive_alter.test:5
CREATE TABLE "MyTable"(i integer, "BigColumn" integer);

-- from catalog/case_insensitive_alter.test:8
ALTER TABLE MyTable ALTER BIGCOLUMN SET DATA TYPE VARCHAR;

-- from catalog/case_insensitive_alter.test:11
ALTER TABLE MyTable DROP COLUMN BIGCOLUMN;

-- from catalog/case_insensitive_alter.test:18
ALTER TABLE MyTable ADD COLUMN "BIGCOLUMN" VARCHAR;
