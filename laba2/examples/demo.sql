-- Демонстрационный скрипт: java -cp out minidb.ConsoleApp data --script examples/demo.sql
CREATE TABLE owners (id INT PRIMARY KEY, name STRING, born DATE);
CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, is_new BOOLEAN, owner_id INT REFERENCES owners(id));
INSERT INTO owners VALUES (1, 'Anna', '1990-05-17'), (2, 'Boris', '1985-11-02');
INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE, 1);
INSERT INTO cars (id, brand) VALUES (2, 'BMW');
INSERT INTO cars VALUES (3, 'Audi', 31000.5, FALSE, 2), (4, 'Toyota', 18000, FALSE, 2);
SELECT * FROM cars;
SELECT brand, price FROM cars WHERE price > 20000;
UPDATE cars SET price = 30000 WHERE id = 2;
CREATE INDEX idx_cars_brand ON cars (brand);
SELECT * FROM cars WHERE brand = 'Toyota';
INSERT INTO cars VALUES (5, 'Kia', 9000.0, TRUE, 99);
DELETE FROM owners WHERE id = 1;
DELETE FROM cars WHERE brand = 'Toyota';
SELECT * FROM cars ORDER BY price DESC;
SHOW TABLES;
DESCRIBE cars;
