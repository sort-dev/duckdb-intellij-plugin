-- from merge/merge_into.test:5
CREATE TABLE Stock(item_id int, balance int);

-- from merge/merge_into.test:8
CREATE TABLE Buy(item_id int, volume int);

-- from merge/merge_into.test:11
INSERT INTO Buy values(10, 1000);

-- from merge/merge_into.test:14
INSERT INTO Buy values(30, 300);
