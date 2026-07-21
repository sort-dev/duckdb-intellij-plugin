COPY (SELECT id, amount FROM orders) TO 'out.parquet' (FORMAT parquet);

COPY orders FROM 'in.csv' (HEADER);
