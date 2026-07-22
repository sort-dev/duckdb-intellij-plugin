-- from storage/types/struct/default_struct.test:8
CREATE TABLE a(i ROW(a INT, b INT) DEFAULT ({'a': 7, 'b': 2}));

-- from storage/types/struct/default_struct.test:11
INSERT INTO a VALUES (DEFAULT);

-- from storage/types/struct/default_struct.test:14
SELECT * FROM a;

-- from storage/types/struct/nested_struct_storage.test:12
CREATE TABLE a AS SELECT {
	'r1': {
		'a': 'hello',
		'b': 3
	},
	'r2': {
		'a': 'world',
		'b': 17,
		'c': NULL
	}
} c;
