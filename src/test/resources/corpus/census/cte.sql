-- from cte/cte_colname_issue_10074.test:5
pragma enable_verification;

-- from cte/cte_colname_issue_10074.test:8
create table t as with q(id,s) as (values(1,42)),
a(s)as materialized(select 42)
select id from q join a on q.s=a.s;

-- from cte/cte_colname_issue_10074.test:13
select id from t;

-- from cte/cte_describe.test:5
PRAGMA enable_verification;
