-- from storage/compression/string/big_strings.test:8
pragma verify_fetch_row;

-- from storage/compression/string/big_strings.test:13
SET enable_fsst_vectors='true';

-- from storage/compression/string/big_strings.test:18
PRAGMA force_compression='fsst';

-- from storage/compression/string/big_strings.test:22
CREATE TABLE normal_string (a VARCHAR);
