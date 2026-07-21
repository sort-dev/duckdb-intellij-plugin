-- from join/right_outer/right_join_complex_null.test:5
PRAGMA enable_verification;

-- from join/right_outer/right_join_complex_null.test:8
pragma verify_external;

-- from join/right_outer/right_join_complex_null.test:11
CREATE TABLE t0(c0 DATE, PRIMARY KEY(c0));

-- from join/right_outer/right_join_complex_null.test:14
CREATE TABLE t1(c0 VARCHAR DEFAULT(DATE '1969-12-10'), c1 DOUBLE DEFAULT(0.16338108651823613));
