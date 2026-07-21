-- from function/list/aggregates/any_value.test:5
SELECT list_aggr([NULL, 1, 2], 'any_value');

-- from function/list/aggregates/any_value.test:20
CREATE TABLE five AS SELECT LIST(i::<numeric>) AS i FROM range(1, 6, 1) t1(i);

-- from function/list/aggregates/any_value.test:23
INSERT INTO five VALUES (NULL), ([NULL]), ([]), ([NULL, 1, 2]);

-- from function/list/aggregates/any_value.test:26
SELECT list_any_value(i) FROM five;
