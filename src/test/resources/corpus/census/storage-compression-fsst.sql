-- from storage/compression/fsst/issue_5759.test:7
pragma force_compression='fsst';

-- from storage/compression/fsst/issue_5759.test:11
CREATE TABLE trigger5759 AS SELECT CASE WHEN RANDOM() > 0.95 THEN repeat('ab', 1500) ELSE 'c' END FROM range(0,1000);
