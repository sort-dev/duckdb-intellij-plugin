SELECT j -> 'key' AS as_json,
       j ->> 'key' AS as_text,
       j -> '$.items[0]' AS first_item,
       json_extract(j, '$.name') AS extracted,
       '{"a": 1}'::JSON AS parsed
FROM docs;
