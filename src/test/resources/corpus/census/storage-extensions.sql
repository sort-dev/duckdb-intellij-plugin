-- from storage/extensions/extension_default.test:8
CREATE TABLE t1(v VARCHAR DEFAULT CURRENT_SCHEMA());

-- from storage/extensions/extension_default.test:13
INSERT INTO t1 VALUES (DEFAULT);

-- from storage/extensions/extension_default.test:16
SELECT * FROM t1;

-- from storage/extensions/extension_views.test:8
CREATE VIEW v1 AS SELECT current_schema();
