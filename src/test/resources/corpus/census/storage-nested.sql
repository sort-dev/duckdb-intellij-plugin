-- from storage/nested/struct_of_lists_unaligned.test:7
CREATE TABLE test_list_2 (a integer, b STRUCT(c VARCHAR[], d VARCHAR[], e INTEGER[]));

-- from storage/nested/struct_of_lists_unaligned.test:10
INSERT INTO test_list_2 SELECT 1, row(['a', 'b', 'c', 'd', 'e', 'f'], ['A', 'B'], [1, 5, 9]) FROM range(10);

-- from storage/nested/struct_of_lists_unaligned.test:13
CHECKPOINT;
