-- from optimizer/expression/test_casting_negative_integer_to_bit.test:5
CREATE TABLE  t1 as select -1 c1 from range(1);

-- from optimizer/expression/test_casting_negative_integer_to_bit.test:8
SELECT t1.c1 FROM t1;

-- from optimizer/expression/test_casting_negative_integer_to_bit.test:13
SELECT CAST(CAST(t1.c1 AS BIT) AS INTEGER), (1 BETWEEN -1 AND CAST(CAST(t1.c1 AS BIT) AS INTEGER)) FROM t1;

-- from optimizer/expression/test_casting_negative_integer_to_bit.test:20
select cast(cast(c1 as BIT) as INTEGER) as cast_res,  1 between -1 and cast(cast(c1 as BIT) as INTEGER) as watever from t1;
