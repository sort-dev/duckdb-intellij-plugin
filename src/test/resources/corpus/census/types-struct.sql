-- from types/struct/create_qualified_type_array.test:7
create type u as struct (i int, j int);

-- from types/struct/create_qualified_type_array.test:20
select cast (null as u array);

-- from types/struct/create_qualified_type_array.test:25
select cast (null as main.u);

-- from types/struct/create_qualified_type_array.test:30
select cast (null as main.u[]);
