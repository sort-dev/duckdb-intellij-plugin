-- from types/enum/standalone_enum.test:5
PRAGMA enable_verification;

-- from types/enum/standalone_enum.test:8
SELECT 'hello'::ENUM('world', 'hello');

-- from types/enum/standalone_enum.test:13
CREATE TABLE test AS SELECT 'hello'::ENUM('world', 'hello') AS h;

-- from types/enum/standalone_enum.test:16
SELECT * FROM test;
