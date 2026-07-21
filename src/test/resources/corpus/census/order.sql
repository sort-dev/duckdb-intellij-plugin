-- from order/hugeint_order_by_extremes.test:5
PRAGMA enable_verification;

-- from order/hugeint_order_by_extremes.test:8
CREATE TABLE test (a hugeint);

-- from order/hugeint_order_by_extremes.test:11
INSERT INTO test values ((-170141183460469231731687303715884105728)::hugeint), (-1111::hugeint), (-1::hugeint), (0::hugeint), (1::hugeint), (1111::hugeint);

-- from order/hugeint_order_by_extremes.test:14
SELECT * FROM test order by a;
