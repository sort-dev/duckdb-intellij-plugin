-- from storage/metadata/full_table_metadata_reuse.test:10
CREATE TABLE bigtbl(i INT);

-- from storage/metadata/full_table_metadata_reuse.test:13
INSERT INTO bigtbl FROM range(1000000);

-- from storage/metadata/full_table_metadata_reuse.test:16
CREATE TABLE little_tbl(i INT);

-- from storage/metadata/full_table_metadata_reuse.test:25
INSERT INTO little_tbl VALUES (1);
