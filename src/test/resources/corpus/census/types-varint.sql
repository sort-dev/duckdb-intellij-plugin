-- from types/varint/test_big_varint.test:5
PRAGMA enable_verification;

-- from types/varint/test_big_varint.test:8
create table t as select concat('1', repeat('0', i))::varint as a from range(0,100) tbl(i);

-- from types/varint/test_big_varint.test:11
select sum(a) from t;

-- from types/varint/test_big_varint.test:16
select sum(a) from t where a < 10000000::DOUBLE;
