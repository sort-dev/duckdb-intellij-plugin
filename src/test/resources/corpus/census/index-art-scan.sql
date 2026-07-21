-- from index/art/scan/test_art_adaptive_scan.test:5
PRAGMA enable_verification;

-- from index/art/scan/test_art_adaptive_scan.test:8
CREATE TABLE integers AS SELECT 42 AS i FROM range(2050);

-- from index/art/scan/test_art_adaptive_scan.test:11
INSERT INTO integers SELECT 42 + 1 + range FROM range(5000);

-- from index/art/scan/test_art_adaptive_scan.test:14
CREATE INDEX i_index ON integers USING ART(i);
