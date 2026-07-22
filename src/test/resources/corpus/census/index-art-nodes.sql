-- from index/art/nodes/test_art_leaf_coverage.test:9
CREATE TABLE duplicates (id UBIGINT);

-- from index/art/nodes/test_art_leaf_coverage.test:12
INSERT INTO duplicates SELECT range + 500 FROM range(500);

-- from index/art/nodes/test_art_leaf_coverage.test:17
INSERT INTO duplicates SELECT range FROM range(500);

-- from index/art/nodes/test_art_leaf_coverage.test:22
INSERT INTO duplicates SELECT range + 1000 FROM range(500);
