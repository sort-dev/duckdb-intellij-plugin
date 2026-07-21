-- from alter/rename_view/test_rename_view.test:5
CREATE TABLE tbl(i INTEGER);
INSERT INTO tbl VALUES (999), (100);
CREATE VIEW vw AS SELECT * FROM tbl;

-- from alter/rename_view/test_rename_view.test:10
BEGIN TRANSACTION;

-- from alter/rename_view/test_rename_view.test:13
ALTER VIEW vw RENAME TO vw2;

-- from alter/rename_view/test_rename_view.test:16
SELECT * FROM vw2;
