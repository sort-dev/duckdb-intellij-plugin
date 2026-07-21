-- from attach/attach_dependencies.test:9
CREATE TABLE pk_tbl (id INTEGER PRIMARY KEY, name VARCHAR UNIQUE);

-- from attach/attach_dependencies.test:12
CREATE TABLE fk_tbl (id INTEGER REFERENCES pk_tbl(id));

-- from attach/attach_dependencies.test:19
CREATE TABLE tbl_alter_column (id INT, other INT, nn_col INT NOT NULL, rm INT, rename_c INT, my_def INT, drop_def INT DEFAULT 10, new_null_col INT);

-- from attach/attach_dependencies.test:22
ALTER TABLE tbl_alter_column ADD COLUMN k INTEGER;
