-- from function/enum/test_enum_code.test:5
PRAGMA enable_verification;

-- from function/enum/test_enum_code.test:8
CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy', 'anxious');

-- from function/enum/test_enum_code.test:11
CREATE TABLE test (x mood);

-- from function/enum/test_enum_code.test:14
INSERT INTO test VALUES ('ok'), ('sad'), ('anxious'), ('happy');
