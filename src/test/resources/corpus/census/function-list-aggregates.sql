-- from function/list/aggregates/any_value.test:5
SELECT list_aggr([NULL, 1, 2], 'any_value');

-- from function/list/aggregates/any_value.test:23
INSERT INTO five VALUES (NULL), ([NULL]), ([]), ([NULL, 1, 2]);

-- from function/list/aggregates/any_value.test:26
SELECT list_any_value(i) FROM five;

-- from function/list/aggregates/any_value.test:35
DROP TABLE five;
