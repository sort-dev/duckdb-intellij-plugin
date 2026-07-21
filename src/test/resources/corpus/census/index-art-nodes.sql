-- from index/art/nodes/test_art_leaf_coverage.test:5
PRAGMA enable_verification;

-- from index/art/nodes/test_art_leaf_coverage.test:12
CREATE TABLE duplicates (id UBIGINT);

-- from index/art/nodes/test_art_leaf_coverage.test:15
INSERT INTO duplicates SELECT range + 500 FROM range(500);

-- from index/art/nodes/test_art_leaf_coverage.test:20
INSERT INTO duplicates SELECT range FROM range(500);
