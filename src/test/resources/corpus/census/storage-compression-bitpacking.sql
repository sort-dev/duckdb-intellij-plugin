-- from storage/compression/bitpacking/bitpacking_mode.test:5
SET force_compression = 'bitpacking';

-- from storage/compression/bitpacking/bitpacking_mode.test:8
SELECT current_setting('force_bitpacking_mode');

-- from storage/compression/bitpacking/bitpacking_mode.test:20
SET force_bitpacking_mode='auto';

-- from storage/compression/bitpacking/bitpacking_mode.test:23
SELECT current_setting('force_bitpacking_mode')='auto';
