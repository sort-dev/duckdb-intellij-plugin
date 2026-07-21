SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS t(id, name);

INSERT INTO orders (id, amount) VALUES (100, 99.5) RETURNING id, amount;
