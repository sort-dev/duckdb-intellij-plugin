-- from storage/compression/alp/alp_inf_null_nan.test:11
pragma force_compression='uncompressed';

-- from storage/compression/alp/alp_inf_null_nan.test:16
create table tbl1_uncompressed(
	a INTEGER DEFAULT 5,
	b VARCHAR DEFAULT 'test',
	c BOOL DEFAULT false,
	d DOUBLE,
	e TEXT default 'null',
	f FLOAT
);

-- from storage/compression/alp/alp_inf_null_nan.test:26
create table tbl2_uncompressed(
	a INTEGER DEFAULT 5,
	b VARCHAR DEFAULT 'test',
	c BOOL DEFAULT false,
	d DOUBLE,
	e TEXT default 'null',
	f FLOAT
);

-- from storage/compression/alp/alp_inf_null_nan.test:36
create table tbl3_uncompressed(
	a INTEGER DEFAULT 5,
	b VARCHAR DEFAULT 'test',
	c BOOL DEFAULT false,
	d DOUBLE,
	e TEXT default 'null',
	f FLOAT
);
