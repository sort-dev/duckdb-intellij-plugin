-- from catalog/view/recursive_view.test:5
set storage_compatibility_version='v0.10.2';

-- from catalog/view/recursive_view.test:8
PRAGMA enable_verification;

-- from catalog/view/recursive_view.test:11
CREATE TABLE IF NOT EXISTS test (val INTEGER);

-- from catalog/view/recursive_view.test:14
INSERT INTO test(val) VALUES (1), (2), (3);
