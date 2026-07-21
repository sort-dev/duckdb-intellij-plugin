-- from function/uuid/test_uuid.test:5
BEGIN TRANSACTION;

-- from function/uuid/test_uuid.test:8
CREATE TEMPORARY TABLE t1 AS SELECT gen_random_uuid() a FROM range(0, 16);

-- from function/uuid/test_uuid.test:11
CREATE TEMPORARY TABLE t2 AS SELECT uuid() b FROM range(0, 16);

-- from function/uuid/test_uuid.test:14
CREATE TEMPORARY TABLE t3 AS SELECT gen_random_uuid() c FROM range(0, 16);
