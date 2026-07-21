-- from transactions/types/test_hugeint_transactions.test:5
PRAGMA enable_verification;

-- from transactions/types/test_hugeint_transactions.test:9
CREATE TABLE hugeints (h HUGEINT);

-- from transactions/types/test_hugeint_transactions.test:12
INSERT INTO hugeints VALUES (100::HUGEINT), (1023819078293589341789412412), (42);

-- from transactions/types/test_hugeint_transactions.test:16
BEGIN TRANSACTION;
