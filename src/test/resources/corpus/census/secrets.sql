-- from secrets/create_secret_persistence_no_client_context.test:7
PRAGMA enable_verification;

-- from secrets/create_secret_persistence_no_client_context.test:10
set secret_directory='/tmp/duckdb_test/create_secret_persistence_no_client_context';

-- from secrets/create_secret_persistence_no_client_context.test:14
CREATE PERSISTENT SECRET s1 ( TYPE HTTP );

-- from secrets/secret_compatibility_http.test:8
set secret_directory='./data/secrets/http';
