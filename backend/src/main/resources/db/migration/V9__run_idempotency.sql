CREATE TABLE idempotency_keys (
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (owner_id, idempotency_key),
    CONSTRAINT fk_idempotency_run FOREIGN KEY (run_id) REFERENCES research_runs (run_id)
) ENGINE=InnoDB;
