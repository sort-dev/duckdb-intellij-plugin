-- from storage/types/list/default_list.test:8
CREATE TABLE a(i INT[] DEFAULT ([1, 2, 3]));

-- from storage/types/list/default_list.test:11
INSERT INTO a VALUES (DEFAULT);

-- from storage/types/list/default_list.test:14
SELECT * FROM a;

-- from storage/types/list/empty_float_arrays.test:9
CREATE TABLE test_table (
    id INTEGER,
    emb FLOAT[],
    emb_arr FLOAT[3]
);
