-- from cte/materialized/annotated_and_auto_materialized.test:5
create table batch (
    entity text,
    start_ts timestamp,
    duration interval
);

-- from cte/materialized/annotated_and_auto_materialized.test:12
create table active_events (
    entity text,
    start_ts timestamp,
    end_ts timestamp
);

-- from cte/materialized/annotated_and_auto_materialized.test:19
explain create table new_active_events as
with
  new_events as materialized (  -- Does not make much sense in this example, but my original query was a union of a bunch of things
      select * from batch
  ), combined_deduplicated_events as (
      select
          entity,
          min(start_ts) as start_ts,
          max(end_ts) as end_ts
      from
          active_events
      group by
          entity
  ), all_events as (
      select  * from combined_deduplicated_events
  )
select
  *
from
  new_events;

-- from cte/materialized/cte_filter_pusher.test:5
WITH
  a(x) AS MATERIALIZED (
    SELECT *
    FROM   generate_series(1, 10)
  ),
  b(x) AS MATERIALIZED (
    SELECT *
    FROM   a
    WHERE  x < 8
  )
SELECT *
FROM   b
WHERE  x % 3 = 1
ORDER BY x;
