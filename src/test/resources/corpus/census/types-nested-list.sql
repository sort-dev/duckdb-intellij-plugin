-- from types/nested/list/any_list.test:5
PRAGMA enable_verification;

-- from types/nested/list/any_list.test:9
SELECT 1=ALL([1, 2, 3]);

-- from types/nested/list/any_list.test:14
SELECT 1=ALL([1, 2, 3, NULL]);

-- from types/nested/list/any_list.test:19
SELECT 1=ANY([1, 2, 3]);
