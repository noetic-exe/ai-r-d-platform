CREATE TABLE worker_slot (id INT PRIMARY KEY, owner VARCHAR(100), lease_until BIGINT NOT NULL DEFAULT 0);
INSERT INTO worker_slot(id,lease_until) VALUES(1,0);
