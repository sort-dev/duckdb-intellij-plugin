-- from storage/constraints/foreignkey/foreign_key_persistent.test:7
CREATE TABLE pk_integers (i INTEGER PRIMARY KEY);

-- from storage/constraints/foreignkey/foreign_key_persistent.test:10
INSERT INTO pk_integers VALUES (1), (2), (3);

-- from storage/constraints/foreignkey/foreign_key_persistent.test:13
CREATE TABLE fk_integers (j INTEGER, FOREIGN KEY (j) REFERENCES pk_integers(i));

-- from storage/constraints/foreignkey/foreign_key_persistent.test:16
INSERT INTO fk_integers VALUES (1), (2);
