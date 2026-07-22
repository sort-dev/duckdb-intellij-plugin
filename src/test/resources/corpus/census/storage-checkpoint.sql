-- from storage/checkpoint/checkpoint_with_outstanding_insertions.test:7
create or replace table z(id integer);

-- from storage/checkpoint/checkpoint_with_outstanding_insertions.test:10
insert into z from range(200_000);

-- from storage/checkpoint/checkpoint_with_outstanding_insertions.test:13
set checkpoint_threshold='1TB';

-- from storage/checkpoint/checkpoint_with_outstanding_insertions.test:16
set immediate_transaction_mode=true;
