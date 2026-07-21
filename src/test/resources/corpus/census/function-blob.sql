-- from function/blob/base64.test:5
PRAGMA enable_verification;

-- from function/blob/base64.test:9
SELECT base64(encode(''));

-- from function/blob/base64.test:14
SELECT base64(encode('a'));

-- from function/blob/base64.test:19
SELECT base64(encode('ab'));
