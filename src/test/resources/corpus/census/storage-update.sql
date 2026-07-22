-- from storage/update/dictionary_update_null.test:8
SET force_compression='dictionary';

-- from storage/update/dictionary_update_null.test:11
CREATE OR REPLACE TABLE 'everflow_daily' AS SELECT case when i%10=0 THEN uuid()::VARCHAR ELSE 'N/A' END sub4 FROM range(10000) t(i);

-- from storage/update/dictionary_update_null.test:14
UPDATE everflow_daily SET sub4 = NULL WHERE sub4 = 'N/A';

-- from storage/update/dictionary_update_null.test:17
select count(*) from everflow_daily
where sub4 = 'N/A';
