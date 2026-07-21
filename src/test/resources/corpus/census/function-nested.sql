-- from function/nested/array_extract_unnamed_struct.test:5
PRAGMA enable_verification;

-- from function/nested/array_extract_unnamed_struct.test:13
SELECT (ROW(42, 84))[1];

-- from function/nested/array_extract_unnamed_struct.test:18
SELECT (ROW(42, 84))[2];

-- from function/nested/array_extract_unnamed_struct.test:23
SELECT UNNEST(ROW(42, 84));
