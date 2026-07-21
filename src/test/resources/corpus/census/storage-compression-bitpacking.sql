-- from storage/compression/bitpacking/bitpacking_mode.test:5
PRAGMA force_compression = 'bitpacking';

-- from storage/compression/bitpacking/bitpacking_mode.test:8
SELECT current_setting('force_bitpacking_mode');

-- from storage/compression/bitpacking/bitpacking_mode.test:21
PRAGMA force_bitpacking_mode='auto';

-- from storage/compression/bitpacking/bitpacking_mode.test:24
SELECT current_setting('force_bitpacking_mode')='auto';
