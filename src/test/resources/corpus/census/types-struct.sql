-- from types/struct/struct_case.test:5
PRAGMA enable_verification;

-- from types/struct/struct_case.test:9
SELECT CASE WHEN 1=1 THEN {'i': 1} ELSE {'i': 2} END;

-- from types/struct/struct_case.test:14
SELECT CASE WHEN 1=0 THEN {'i': 1} ELSE {'i': 2} END;

-- from types/struct/struct_case.test:20
SELECT CASE WHEN 1=1 THEN NULL ELSE {'i': 2} END;
