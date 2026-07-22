-- from ordinality/ordinality_inout.test:5
PRAGMA enable_verification;

-- from ordinality/ordinality_inout.test:9
CREATE TABLE test(a int);

-- from ordinality/ordinality_inout.test:13
INSERT INTO test VALUES (1),(3);

-- from ordinality/ordinality_inout.test:17
SELECT a,my_range,my_ordinality FROM test AS t(a), LATERAL range(t.a) WITH ORDINALITY AS _(my_range,my_ordinality) ORDER BY a,my_range;
