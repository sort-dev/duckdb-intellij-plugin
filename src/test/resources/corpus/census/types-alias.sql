-- from types/alias/nested_alias.test:5
CREATE TYPE my_int AS INT;

-- from types/alias/nested_alias.test:8
CREATE TYPE my_int_list AS my_int[];

-- from types/alias/nested_alias.test:11
SELECT [42]::my_int_list;

-- from types/alias/test_alias.test:5
PRAGMA enable_verification;
