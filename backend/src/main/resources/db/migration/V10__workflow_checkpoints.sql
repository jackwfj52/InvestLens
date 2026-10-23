ALTER TABLE run_steps ADD UNIQUE KEY uk_step_run_identity (run_id,step_id);
CREATE TABLE step_outputs (
    output_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_version BIGINT NOT NULL,
    input_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    output_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    output_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (output_id),
    UNIQUE KEY uk_output_execution (step_id,execution_version),
    CONSTRAINT fk_output_step FOREIGN KEY (run_id,step_id) REFERENCES run_steps (run_id,step_id),
    CONSTRAINT ck_output_token CHECK (execution_version > 0)
) ENGINE=InnoDB;
