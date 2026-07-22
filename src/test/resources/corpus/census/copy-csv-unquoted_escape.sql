-- from copy/csv/unquoted_escape/basic.test:5
PRAGMA enable_verification;

-- from copy/csv/unquoted_escape/basic.test:8
SELECT * FROM read_csv('{DATA_DIR}/csv/unquoted_escape/plain.csv', escape = '\', sep = ',', strict_mode = false, nullstr = '\N');

-- from copy/csv/unquoted_escape/basic.test:22
CREATE TABLE special_char(a INT, b STRING);

-- from copy/csv/unquoted_escape/basic.test:25
INSERT INTO special_char VALUES
    (0, E'\\'), (1, E'\t'), (2, E'\n'),
    (3, E'a\\a'), (4, E'b\tb'), (5, E'c\nc'),
    (6, E'\\d'), (7, E'\te'), (8, E'\nf'),
    (9, E'g\\'), (10, E'h\t'), (11, E'i\n'),
    (12, E'\\j'), (13, E'\tk'), (14, E'\nl'),
    (15, E'\\\\'), (16, E'\t\t'), (17, E'\n\n'),
    (18, E'\\\t\n');
