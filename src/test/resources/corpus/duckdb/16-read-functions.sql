SELECT * FROM read_parquet('f.parquet');

SELECT * FROM read_csv('f.csv', header = true, delim = ',');

SELECT * FROM read_parquet('data/*.parquet');
