-- from join/positional/issue20086.test:5
PRAGMA wal_autocheckpoint='1TB';

-- from join/positional/issue20086.test:8
PRAGMA disable_checkpoint_on_shutdown;

-- from join/positional/issue20086.test:11
CREATE TABLE t0(c0 INT, c1 INT);

-- from join/positional/issue20086.test:14
CREATE TABLE t1(c0 INT);
