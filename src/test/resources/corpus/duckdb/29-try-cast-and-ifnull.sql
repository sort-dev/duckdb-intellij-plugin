SELECT TRY_CAST('abc' AS INTEGER) AS maybe_int,
       COALESCE(a, b, 0) AS first_non_null,
       ifnull(a, -1) AS with_default
FROM (SELECT NULL::INTEGER AS a, 7 AS b) t;
