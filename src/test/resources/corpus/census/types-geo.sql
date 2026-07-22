-- from types/geo/geometry.test:4
create table t1(id INT, g GEOMETRY);

-- from types/geo/geometry.test:10
insert into t1 values
	(1, 'POINT(0 1)'),
	(2, 'LINESTRING(0 0, 1 1, 2 2)'),
	(3, 'POLYGON((0 0, 4 0, 4 4, 0 4, 0 0))'),
	(4, 'MULTIPOINT((1 1), (2 2), (3 3))'),
	(5, 'MULTIPOINT(1 1, 2 2, 3 3)'), --alternative syntax
	(6, 'MULTILINESTRING((0 0, 1 1), (2 2, 3 3))'),
	(7, 'MULTIPOLYGON(((0 0, 4 0, 4 4, 0 4, 0 0)), ((5 5, 7 5, 7 7, 5 7, 5 5)))'),
	(8, 'GEOMETRYCOLLECTION(POINT(1 1), LINESTRING(0 0, 1 1))'),
	(9, NULL);

-- from types/geo/geometry.test:22
select id, g::VARCHAR from t1 order by id;

-- from types/geo/geometry.test:36
create table t2(id INT, g GEOMETRY);
