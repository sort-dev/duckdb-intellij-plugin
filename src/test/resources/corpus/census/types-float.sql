-- from types/float/ieee_floating_points.test:5
PRAGMA enable_verification;

-- from types/float/ieee_floating_points.test:10
CREATE OR REPLACE TABLE tbl(val FLOAT);

-- from types/float/ieee_floating_points.test:13
INSERT INTO tbl VALUES (1), (-1), (0), ('nan'), ('inf');

-- from types/float/ieee_floating_points.test:17
SELECT val, val / 0::FLOAT FROM tbl;
