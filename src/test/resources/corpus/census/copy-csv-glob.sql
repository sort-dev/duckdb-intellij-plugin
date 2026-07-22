-- from copy/csv/glob/copy_csv_glob.test:5
PRAGMA enable_verification;

-- from copy/csv/glob/copy_csv_glob.test:8
CREATE TABLE dates(d DATE);

-- from copy/csv/glob/copy_csv_glob.test:12
COPY dates FROM '{DATA_DIR}/csv/glob/a?/*.csv' (AUTO_DETECT 1);

-- from copy/csv/glob/copy_csv_glob.test:15
SELECT * FROM dates ORDER BY 1;
