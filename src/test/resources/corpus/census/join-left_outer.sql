-- from join/left_outer/left_join_issue_1172.test:5
SET default_null_order='nulls_first';

-- from join/left_outer/left_join_issue_1172.test:8
PRAGMA enable_verification;

-- from join/left_outer/left_join_issue_1172.test:11
pragma verify_external;

-- from join/left_outer/left_join_issue_1172.test:14
drop table if exists t1;
