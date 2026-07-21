-- from types/decimal/cast_from_decimal.test:5
PRAGMA enable_verification;

-- from types/decimal/cast_from_decimal.test:9
SELECT 127::DECIMAL(3,0)::TINYINT, -127::DECIMAL(3,0)::TINYINT, -7::DECIMAL(9,1)::TINYINT, 27::DECIMAL(18,1)::TINYINT, 33::DECIMAL(38,1)::TINYINT;

-- from types/decimal/cast_from_decimal.test:35
SELECT 127::DECIMAL(3,0)::SMALLINT, -32767::DECIMAL(5,0)::SMALLINT, -7::DECIMAL(9,1)::SMALLINT, 27::DECIMAL(18,1)::SMALLINT, 33::DECIMAL(38,1)::SMALLINT;

-- from types/decimal/cast_from_decimal.test:56
SELECT 127::DECIMAL(3,0)::INTEGER, -2147483647::DECIMAL(10,0)::INTEGER, -7::DECIMAL(9,1)::INTEGER, 27::DECIMAL(18,1)::INTEGER, 33::DECIMAL(38,1)::INTEGER;
