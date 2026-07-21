-- from types/uhugeint/test_uhugeint_aggregates.test:5
PRAGMA enable_verification;

-- from types/uhugeint/test_uhugeint_aggregates.test:8
CREATE TABLE hugeints(g INTEGER, h UHUGEINT);

-- from types/uhugeint/test_uhugeint_aggregates.test:11
INSERT INTO hugeints VALUES (1, 42), (2, 1267650600228229401496703205376), (2, 0), (1, '8');

-- from types/uhugeint/test_uhugeint_aggregates.test:16
SELECT MIN(h), MAX(h), SUM(h), FIRST(h), LAST(h) FROM hugeints;
