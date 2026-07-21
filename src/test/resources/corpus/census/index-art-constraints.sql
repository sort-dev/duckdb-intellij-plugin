-- from index/art/constraints/test_art_compound_key_changes.test:5
PRAGMA enable_verification;

-- from index/art/constraints/test_art_compound_key_changes.test:8
SET immediate_transaction_mode = true;

-- from index/art/constraints/test_art_compound_key_changes.test:11
CREATE TABLE tbl_comp (
	a INT,
	b VARCHAR UNIQUE,
	gen AS (2 * a),
	c INT,
	d VARCHAR,
	PRIMARY KEY (c, b));

-- from index/art/constraints/test_art_compound_key_changes.test:20
CREATE UNIQUE INDEX unique_idx ON tbl_comp((d || 'hello'), (a + 42));
