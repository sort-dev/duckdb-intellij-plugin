-- from types/nested/struct/struct_aggregates.test:5
PRAGMA enable_verification;

-- from types/nested/struct/struct_aggregates.test:8
select min(struct_pack(i :=  i, j := i + 2)), max(struct_pack(i :=  i, j := i + 2)), first(struct_pack(i :=  i, j := i + 2)) from range(10) tbl(i);

-- from types/nested/struct/struct_aggregates.test:14
select min(struct_pack(i := -i, j := -i - 2)), max(struct_pack(i := i + 2, j := i + 4)), first(struct_pack(i :=  i, j := i + 2)) from range(10) tbl(i);

-- from types/nested/struct/struct_aggregates.test:19
select string_agg(struct_pack(i :=  i, j := i + 2)::VARCHAR, ',') from range(10) tbl(i);
