-- from types/type/test_make_get_type.test:4
SELECT get_type(NULL);

-- from types/type/test_make_get_type.test:9
SELECT get_type(1);

-- from types/type/test_make_get_type.test:14
SELECT get_type('hello');

-- from types/type/test_make_get_type.test:19
SELECT make_type('STRUCT', a := make_type('INTEGER'), b := make_type('VARCHAR'));
