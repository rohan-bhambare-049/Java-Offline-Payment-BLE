-- Mesh packet receipts table for the Bluetooth mesh feature.
--
-- Run this once against the same MySQL database configured in
-- application.properties, before starting the extended app.
-- The app uses spring.jpa.hibernate.ddl-auto=validate, so it will
-- NOT create this table for you. No existing table is changed.
-- If the table already exists, this statement does nothing harmful
-- because of IF NOT EXISTS, but the unique constraint must exist
-- for the database layer of idempotency to work. Check it with
-- SHOW CREATE TABLE mesh_packet_receipts after running this file.

CREATE TABLE IF NOT EXISTS mesh_packet_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    packet_hash VARCHAR(64) NOT NULL,
    packet_id VARCHAR(36) NOT NULL,
    origin_device_id VARCHAR(100) NOT NULL,
    bridge_node_id VARCHAR(100) NULL,
    hop_count INT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    instruction_uuid VARCHAR(36) NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_mesh_packet_hash UNIQUE (packet_hash),
    INDEX idx_mesh_packet_id (packet_id),
    INDEX idx_mesh_packet_instruction (instruction_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
