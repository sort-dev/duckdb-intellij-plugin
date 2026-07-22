-- from function/interval/test_date_part.test:5
CREATE TABLE intervals(i INTERVAL, s VARCHAR);

-- from function/interval/test_date_part.test:8
INSERT INTO intervals VALUES ('2 years', 'year'), ('16 months', 'quarter'), ('42 days', 'day'), ('2066343400 microseconds', 'minute');

-- from function/interval/test_date_part.test:12
SELECT date_part(NULL::VARCHAR, NULL::INTERVAL) FROM intervals;

-- from function/interval/test_date_part.test:20
SELECT date_part(s, NULL::INTERVAL) FROM intervals;
