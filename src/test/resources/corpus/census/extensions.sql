-- from extensions/allowed_directories_install.test:5
set extension_directory='/tmp/duckdb_test/extension_dir';

-- from extensions/allowed_directories_install.test:8
SET allowed_directories=['/tmp/duckdb_test', 'http://', 'https://'];

-- from extensions/allowed_directories_install.test:11
SET enable_external_access=false;

-- from extensions/allowed_directories_install.test:21
set extension_directory='/tmp';
