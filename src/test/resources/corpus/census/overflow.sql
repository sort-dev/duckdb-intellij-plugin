-- from overflow/bigint_overflow.test:5
PRAGMA enable_verification;

-- from overflow/bigint_overflow.test:8
SELECT 251658240::BIGINT * 251658240::BIGINT;

-- from overflow/bigint_overflow.test:28
SELECT -1::BIGINT * 9223372036854775807::BIGINT;

-- from overflow/bigint_overflow.test:38
SELECT 8589934592::BIGINT * 1073741823::BIGINT;
