-- from alter/add_pk/test_add_multi_column_pk.test:5
PRAGMA enable_verification;

-- from alter/add_pk/test_add_multi_column_pk.test:8
CREATE TABLE test (i INTEGER, j INTEGER, d TEXT);

-- from alter/add_pk/test_add_multi_column_pk.test:11
INSERT INTO test VALUES (3, 4, 'hello'), (44, 45, '56');

-- from alter/add_pk/test_add_multi_column_pk.test:14
ALTER TABLE test ADD PRIMARY KEY (i, j);
