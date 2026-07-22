-- from join/empty_joins.test:5
CREATE TABLE integers AS SELECT i FROM range(10) tbl(i);

-- from join/empty_joins.test:8
CREATE TABLE integers2 AS SELECT i FROM range(10) tbl(i);

-- from join/empty_joins.test:11
CREATE VIEW integers_empty AS SELECT * FROM integers WHERE rowid>100;

-- from join/empty_joins.test:14
CREATE VIEW integers2_empty AS SELECT * FROM integers WHERE rowid>100;
