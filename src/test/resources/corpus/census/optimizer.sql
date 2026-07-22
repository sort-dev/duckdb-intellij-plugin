-- from optimizer/predicate_factoring.test:5
PRAGMA enable_verification;

-- from optimizer/predicate_factoring.test:8
CREATE TABLE t (a INTEGER, b INTEGER, c INTEGER);

-- from optimizer/predicate_factoring.test:11
INSERT INTO t VALUES (1, 5, 3), (1, 2, 3), (1, 5, 11), (2, 5, 3), (NULL, 5, 3);

-- from optimizer/predicate_factoring.test:15
SELECT * FROM t WHERE (a=1 AND b>3) OR (a=1 AND c<5);
