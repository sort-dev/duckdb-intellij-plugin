-- from json/issues/large_quoted_string_constant.test:5
CREATE TABLE j2 (id INT, json VARCHAR, src VARCHAR);

-- from json/issues/large_quoted_string_constant.test:81
SELECT len(json) FROM j2;
