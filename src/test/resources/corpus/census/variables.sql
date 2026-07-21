-- from variables/test_variables.test:5
PRAGMA enable_verification;

-- from variables/test_variables.test:8
SET VARIABLE animal = 'duck';

-- from variables/test_variables.test:11
SELECT GETVARIABLE('animal');

-- from variables/test_variables.test:16
PREPARE v1 AS SELECT GETVARIABLE($1);
