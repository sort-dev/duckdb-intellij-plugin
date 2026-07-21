-- from join/cross_product/test_cross_product.test:5
PRAGMA enable_verification;

-- from join/cross_product/test_cross_product.test:8
CREATE TABLE test (a INTEGER, b INTEGER);

-- from join/cross_product/test_cross_product.test:11
INSERT INTO test VALUES (11, 1), (12, 2);

-- from join/cross_product/test_cross_product.test:14
SELECT * FROM test t1, test t2 ORDER BY 1, 2, 3, 4;
