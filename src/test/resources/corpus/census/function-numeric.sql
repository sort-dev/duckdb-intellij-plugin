-- from function/numeric/abs.test:6
SELECT abs('-0.0'::float), abs('-0.0'::double);

-- from function/numeric/decimal_mod.test:5
SELECT 10 % 2.4, -10 % 2.4;

-- from function/numeric/decimal_mod.test:10
SELECT 10.0 % 2.4, -10.0 % 2.4;

-- from function/numeric/decimal_mod.test:16
SELECT 12345678901111111 % 2.0;
