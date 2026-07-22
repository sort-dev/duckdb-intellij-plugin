-- from catalog/function/attached_macro.test:5
ATTACH ':memory:' AS checksum_macro;

-- from catalog/function/attached_macro.test:8
CREATE MACRO checksum_macro.checksum(table_name) AS TABLE
    SELECT bit_xor(md5_number(COLUMNS(*)::VARCHAR))
    FROM query_table(table_name);

-- from catalog/function/attached_macro.test:18
CREATE TABLE tbl AS SELECT UNNEST([42, 43]) AS x;

-- from catalog/function/attached_macro.test:21
USE checksum_macro;
