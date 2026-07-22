-- from index/art/scan/test_art_adaptive_scan.test:5
CREATE TABLE integers AS SELECT 42 AS i FROM range(2050);

-- from index/art/scan/test_art_adaptive_scan.test:8
INSERT INTO integers SELECT 42 + 1 + range FROM range(5000);

-- from index/art/scan/test_art_adaptive_scan.test:11
CREATE INDEX i_index ON integers USING ART(i);

-- from index/art/scan/test_art_adaptive_scan.test:14
SET index_scan_percentage = 1.0;
