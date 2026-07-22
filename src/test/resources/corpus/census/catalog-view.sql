-- from catalog/view/recursive_view.test:5
set storage_compatibility_version='v0.10.2';

-- from catalog/view/recursive_view.test:8
CREATE TABLE IF NOT EXISTS test (val INTEGER);

-- from catalog/view/recursive_view.test:11
INSERT INTO test(val) VALUES (1), (2), (3);

-- from catalog/view/recursive_view.test:15
CREATE OR REPLACE VIEW foo AS (SELECT * FROM test);
