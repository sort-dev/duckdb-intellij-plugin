-- from storage/catalog/generated_columns/virtual/basic.test:5
PRAGMA enable_verification;

-- from storage/catalog/generated_columns/virtual/basic.test:11
CREATE TABLE tbl (
	price INTEGER,
	gcol AS (price)
);

-- from storage/catalog/generated_columns/virtual/basic.test:19
INSERT INTO tbl VALUES (5);

-- from storage/catalog/generated_columns/virtual/basic.test:22
SELECT gcol FROM tbl;
