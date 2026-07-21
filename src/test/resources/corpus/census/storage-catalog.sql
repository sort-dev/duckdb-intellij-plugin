-- from storage/catalog/store_collate.test:8
CREATE TABLE collate_test(s VARCHAR COLLATE NOACCENT);

-- from storage/catalog/store_collate.test:11
INSERT INTO collate_test VALUES ('Mühleisen'), ('Hëllö');

-- from storage/catalog/store_collate.test:15
SELECT * FROM collate_test WHERE s='Muhleisen';

-- from storage/catalog/store_collate.test:20
SELECT * FROM collate_test WHERE s='mühleisen';
