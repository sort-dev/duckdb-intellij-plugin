-- from function/variant/variant_extract_try_cast.test:6
SET variant_minimum_shredding_size = 0;

-- from function/variant/variant_extract_try_cast.test:9
create table tbl(col VARIANT);

-- from function/variant/variant_extract_try_cast.test:12
insert into tbl SELECT * FROM UNNEST([
	{'almost_a_number': c, 'a_number': CAST(TRY_CAST(c AS INT) AS VARCHAR)} for c in ['12', '24', '25a6', '24c', '16']
]);

-- from function/variant/variant_extract_try_cast.test:17
checkpoint;
