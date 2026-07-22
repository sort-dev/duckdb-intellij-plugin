-- from transactions/statement-preprocessor/invalidation_policy_is_respected_by_preprocessor.test:4
ATTACH '/tmp/duckdb_test/wal_replay.db';

-- from transactions/statement-preprocessor/invalidation_policy_is_respected_by_preprocessor.test:7
USE wal_replay;

-- from transactions/statement-preprocessor/invalidation_policy_is_respected_by_preprocessor.test:10
PRAGMA disable_checkpoint_on_shutdown;

-- from transactions/statement-preprocessor/invalidation_policy_is_respected_by_preprocessor.test:13
SET wal_autocheckpoint='1TB';
