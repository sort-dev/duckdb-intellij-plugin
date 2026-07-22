-- from constraints/foreignkey/fk_19469.test:5
CREATE TABLE B (b1 INTEGER,
b2 INTEGER,
PRIMARY KEY(b1, b2));

-- from constraints/foreignkey/fk_19469.test:10
CREATE TABLE A (a1 VARCHAR(1),
a2 VARCHAR(1),
a3 VARCHAR(1),
a4 VARCHAR(1),
a5 INTEGER,
a6 INTEGER,
PRIMARY KEY(a1, a2),
UNIQUE(a3, a4),
FOREIGN KEY (a5, a6) REFERENCES B(b1, b2));

-- from constraints/foreignkey/fk_19469.test:21
INSERT INTO B (b1, b2) VALUES
(1, 2),
(2, 3),
(6, 7);

-- from constraints/foreignkey/fk_19469.test:36
CREATE TABLE C (
    c1 INTEGER,
    c2 INTEGER,
    c3 VARCHAR(1),
    c4 VARCHAR(1),
    PRIMARY KEY (c1, c2),
    UNIQUE (c3, c4)
);
