ALTER TABLE research_runs ADD CONSTRAINT ck_run_budget_limits
    CHECK (data_attempts <= 8 AND model_attempts <= 5 AND schema_repairs <= 1
        AND model_attempts - schema_repairs <= 4 AND retry_count <= 2 AND schema_repairs <= model_attempts);

CREATE TABLE run_data_windows (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    PRIMARY KEY (run_id,instrument_id),
    CONSTRAINT fk_window_run FOREIGN KEY (run_id) REFERENCES research_runs (run_id),
    CONSTRAINT ck_window_range CHECK (window_end >= window_start)
) ENGINE=InnoDB;

CREATE TABLE tool_calls (
    call_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_version BIGINT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instrument_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    window_start DATE NULL,
    window_end DATE NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    timeout_millis BIGINT NOT NULL,
    started_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (call_id),
    KEY ix_calls_run (run_id,started_at,call_id),
    CONSTRAINT fk_call_step FOREIGN KEY (run_id,step_id) REFERENCES run_steps (run_id,step_id),
    CONSTRAINT ck_call_kind CHECK (kind IN ('DATA','MODEL')),
    CONSTRAINT ck_call_purpose CHECK (purpose IN ('STANDARD','SCHEMA_REPAIR','RETRY') AND (purpose <> 'SCHEMA_REPAIR' OR kind='MODEL')),
    CONSTRAINT ck_call_state CHECK (status IN ('STARTED','SUCCEEDED','FAILED','TIMED_OUT')),
    CONSTRAINT ck_call_window CHECK ((kind='DATA' AND instrument_id IS NOT NULL AND window_start IS NOT NULL AND window_end IS NOT NULL AND window_end >= window_start)
        OR (kind='MODEL' AND instrument_id IS NULL AND window_start IS NULL AND window_end IS NULL)),
    CONSTRAINT ck_call_timeout CHECK (execution_version > 0 AND timeout_millis > 0 AND expires_at > started_at
        AND ((kind='DATA' AND timeout_millis <= 8000) OR (kind='MODEL' AND timeout_millis <= 30000))),
    CONSTRAINT ck_call_finished CHECK ((status='STARTED') = (finished_at IS NULL))
) ENGINE=InnoDB;
