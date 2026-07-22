-- from types/uuid/test_uuid_cast.test:5
PRAGMA enable_verification;

-- from types/uuid/test_uuid_cast.test:9
select try_cast(try_cast('00112233-4455-6677-8899-aabbccddeeff'::UUID AS BLOB) as uuid) as test;

-- from types/uuid/test_uuid_cast.test:15
SELECT '00112233-4455-6677-8899-aabbccddeeff'::UUID::BLOB;

-- from types/uuid/test_uuid_cast.test:21
SELECT '\x00\x11\x223DUfw\x88\x99\xAA\xBB\xCC\xDD\xEE\xFF'::BLOB::UUID;
