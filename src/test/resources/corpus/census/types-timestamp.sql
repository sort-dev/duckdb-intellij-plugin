-- from types/timestamp/alternative_timestamp_casts.test:5
PRAGMA enable_verification;

-- from types/timestamp/alternative_timestamp_casts.test:8
SELECT DATE '1992-01-01'::TIMESTAMP_MS;

-- from types/timestamp/alternative_timestamp_casts.test:13
SELECT DATE '1992-01-01'::TIMESTAMP_S;

-- from types/timestamp/alternative_timestamp_casts.test:18
SELECT DATE '1992-01-01'::TIMESTAMP_NS;
