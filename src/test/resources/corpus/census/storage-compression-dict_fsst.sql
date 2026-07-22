-- from storage/compression/dict_fsst/dict_fsst_test.test:6
pragma force_compression='uncompressed';

-- from storage/compression/dict_fsst/dict_fsst_test.test:9
create table uncompressed_data as
select
	i, repeat(
		(i % 200)::INTEGER::VARCHAR,
		2047 // len((i % 200)::INTEGER::VARCHAR)
	) a
from range(20000) t(i);

-- from storage/compression/dict_fsst/dict_fsst_test.test:18
checkpoint;

-- from storage/compression/dict_fsst/dict_fsst_test.test:21
select * from uncompressed_data order by i;
