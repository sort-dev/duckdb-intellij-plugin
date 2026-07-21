-- from select/test_positional_reference.test:5
PRAGMA enable_verification;

-- from select/test_positional_reference.test:8
SELECT #1 FROM range(1);

-- from select/test_positional_reference.test:14
SELECT #1+#2 FROM range(1) tbl, range(1) tbl2;

-- from select/test_positional_reference.test:20
SELECT #1 FROM (SELECT * FROM range(1)) tbl;
