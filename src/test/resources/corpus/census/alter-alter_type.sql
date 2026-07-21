-- from alter/alter_type/alter_type_struct.test:5
CREATE TABLE test AS SELECT {'t': 42} t;

-- from alter/alter_type/alter_type_struct.test:8
SELECT * FROM test;

-- from alter/alter_type/alter_type_struct.test:13
ALTER TABLE test ALTER t TYPE ROW(t VARCHAR) USING {'t': concat('hello', (test.t.t + 42)::varchar)};

-- from alter/alter_type/alter_type_struct.test:21
DROP TABLE test;
