-- from prepared/invalid_prepare.test:16
prepare v1 as select $2::int;

-- from prepared/invalid_prepare.test:24
prepare v2 as select $1::int;

-- from prepared/invalid_prepare.test:31
prepare v3 as select $1::int where 1=0;

-- from prepared/invalid_prepare.test:34
execute v3(1);
