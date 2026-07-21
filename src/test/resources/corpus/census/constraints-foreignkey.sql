-- from constraints/foreignkey/fk_4309.test:5
PRAGMA enable_verification;

-- from constraints/foreignkey/fk_4309.test:13
CREATE TABLE tf_1 (
  a integer, b integer, c integer,
  PRIMARY KEY (a),
  UNIQUE (b),
  UNIQUE (c)
);

-- from constraints/foreignkey/fk_4309.test:21
CREATE TABLE tf_2 (
  d integer, e integer, f integer,
  FOREIGN KEY (d) REFERENCES tf_1 (a),
  FOREIGN KEY (e) REFERENCES tf_1 (b),
  FOREIGN KEY (f) REFERENCES tf_1 (c)
);

-- from constraints/foreignkey/fk_4309.test:36
INSERT INTO tf_1 VALUES (1, 1, 1);
