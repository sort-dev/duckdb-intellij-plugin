-- from types/numeric/arithmetic_vector_types.test:5
PRAGMA enable_verification;

-- from types/numeric/bigint_try_cast.test:9
CREATE TABLE bigints AS SELECT i::BIGINT i FROM (VALUES (-9223372036854775808), (0), (9223372036854775807)) tbl(i);

-- from types/numeric/bigint_try_cast.test:41
SELECT i::UBIGINT FROM bigints WHERE i>=0 ORDER BY i;

-- from types/numeric/bigint_try_cast.test:47
SELECT TRY_CAST(i AS UTINYINT) FROM bigints ORDER BY i;
