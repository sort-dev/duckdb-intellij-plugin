-- from types/numeric/combinations/decimal_combinations.test:5
PRAGMA enable_verification;

-- from types/numeric/combinations/decimal_combinations.test:8
CREATE TABLE tinyint_limits AS SELECT (-128)::TINYINT min, 127::TINYINT max;

-- from types/numeric/combinations/decimal_combinations.test:11
CREATE TABLE smallint_limits AS SELECT (-32768)::SMALLINT min, 32767::SMALLINT max;

-- from types/numeric/combinations/decimal_combinations.test:14
CREATE TABLE integer_limits AS SELECT (-2147483648)::INTEGER min, 2147483647::INTEGER max;
