-- from types/bignum/test_big_bignum.test:5
PRAGMA enable_verification;

-- from types/bignum/test_big_bignum.test:8
create table t as select concat('1', repeat('0', i))::bignum as a from range(0,100) tbl(i);

-- from types/bignum/test_big_bignum.test:11
select sum(a) from t;

-- from types/bignum/test_big_bignum.test:16
select sum(a) from t where a < 10000000;
