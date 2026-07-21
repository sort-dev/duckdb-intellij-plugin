-- from function/numeric/abs.test:5
PRAGMA enable_verification;

-- from function/numeric/abs.test:9
SELECT abs('-0.0'::float), abs('-0.0'::double);

-- from function/numeric/decimal_mod.test:8
SELECT 10 % 2.4, -10 % 2.4;

-- from function/numeric/decimal_mod.test:13
SELECT 10.0 % 2.4, -10.0 % 2.4;
