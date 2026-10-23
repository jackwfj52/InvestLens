CREATE TABLE research_runs (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_version INT NOT NULL,
    portfolio_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    portfolio_version INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    data_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_quality VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    research_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    report_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    deadline_at DATETIME(3) NOT NULL,
    data_attempts INT NOT NULL DEFAULT 0,
    model_attempts INT NOT NULL DEFAULT 0,
    schema_repairs INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    event_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_run_portfolio (run_id, portfolio_snapshot_id),
    KEY ix_runs_queue (status, created_at, run_id),
    KEY ix_runs_owner (owner_id, created_at, run_id),
    CONSTRAINT fk_run_decision FOREIGN KEY (decision_id, decision_version) REFERENCES decision_versions (decision_id, version),
    CONSTRAINT fk_run_portfolio FOREIGN KEY (portfolio_snapshot_id) REFERENCES portfolio_snapshots (snapshot_id),
    CONSTRAINT ck_run_versions CHECK (decision_version > 0 AND portfolio_version > 0 AND revision >= 0 AND event_sequence >= 0),
    CONSTRAINT ck_run_status CHECK (status IN ('CREATED','QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    CONSTRAINT ck_run_stage CHECK (stage IS NULL OR stage IN ('PLAN','RESEARCH','QUANT','DEBATE','JUDGE','VALIDATE')),
    CONSTRAINT ck_run_mode CHECK (data_mode IN ('LIVE','CACHE','REPLAY','SYNTHETIC')),
    CONSTRAINT ck_run_quality CHECK (data_quality IS NULL OR data_quality IN ('VALID','PARTIAL','UNUSABLE')),
    CONSTRAINT ck_run_counters CHECK (data_attempts >= 0 AND model_attempts >= 0 AND schema_repairs >= 0 AND retry_count >= 0),
    CONSTRAINT ck_run_terminal CHECK ((status IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_run_started CHECK (status <> 'RUNNING' OR (started_at IS NOT NULL AND stage IS NOT NULL)),
    CONSTRAINT ck_run_report CHECK (status NOT IN ('COMPLETED','PARTIAL') OR
        (report_id IS NOT NULL AND research_snapshot_id IS NOT NULL AND snapshot_hash IS NOT NULL)),
    CONSTRAINT ck_run_deadline CHECK (deadline_at > created_at)
) ENGINE=InnoDB;

CREATE TABLE run_steps (
    step_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    input_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    output_ref VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    execution_version BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (step_id),
    UNIQUE KEY uk_run_step_key (run_id, step_key),
    KEY ix_step_lease (status, lease_until),
    CONSTRAINT fk_step_run FOREIGN KEY (run_id) REFERENCES research_runs (run_id),
    CONSTRAINT ck_step_key CHECK (step_key IN ('PLAN','RESEARCH','QUANT','BULL','BEAR','JUDGE','VALIDATE')),
    CONSTRAINT ck_step_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED')),
    CONSTRAINT ck_step_counters CHECK (attempt >= 0 AND execution_version >= 0),
    CONSTRAINT ck_step_lease CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CONSTRAINT ck_step_running CHECK (status <> 'RUNNING' OR (attempt > 0 AND execution_version > 0)),
    CONSTRAINT ck_step_output CHECK (status <> 'SUCCEEDED' OR output_ref IS NOT NULL)
) ENGINE=InnoDB;

CREATE TABLE run_events (
    event_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence BIGINT NOT NULL,
    type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    message VARCHAR(200) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_run_event_sequence (run_id, sequence),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES research_runs (run_id),
    CONSTRAINT ck_event_sequence CHECK (sequence > 0),
    CONSTRAINT ck_event_type CHECK (type IN ('RUN_QUEUED','STEP_STARTED','STEP_SUCCEEDED','STEP_FAILED','RUN_TERMINATED')),
    CONSTRAINT ck_event_status CHECK (status IN ('CREATED','QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    CONSTRAINT ck_event_stage CHECK (stage IS NULL OR stage IN ('PLAN','RESEARCH','QUANT','DEBATE','JUDGE','VALIDATE'))
) ENGINE=InnoDB;

-- Links preserve immutable pre-Run JSON/hash content and allow explicit provenance without fake published bindings.
ALTER TABLE calculation_snapshots ADD UNIQUE KEY uk_calculation_portfolio (snapshot_id, portfolio_snapshot_id);
ALTER TABLE metric_bundles ADD UNIQUE KEY uk_bundle_calculation (bundle_id, calculation_snapshot_id);
CREATE TABLE run_calculation_inputs (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    portfolio_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (run_id, calculation_snapshot_id),
    CONSTRAINT fk_run_input_run FOREIGN KEY (run_id, portfolio_snapshot_id) REFERENCES research_runs (run_id, portfolio_snapshot_id),
    CONSTRAINT fk_run_input_calculation FOREIGN KEY (calculation_snapshot_id, portfolio_snapshot_id) REFERENCES calculation_snapshots (snapshot_id, portfolio_snapshot_id)
) ENGINE=InnoDB;
CREATE TABLE run_metric_bundles (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (run_id, bundle_id),
    CONSTRAINT fk_run_metric_input FOREIGN KEY (run_id, calculation_snapshot_id) REFERENCES run_calculation_inputs (run_id, calculation_snapshot_id),
    CONSTRAINT fk_run_metric_bundle FOREIGN KEY (bundle_id, calculation_snapshot_id) REFERENCES metric_bundles (bundle_id, calculation_snapshot_id)
) ENGINE=InnoDB;
