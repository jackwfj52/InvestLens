CREATE TABLE scenario_results (
    scenario_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    portfolio_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_version INT NOT NULL,
    source_evidence_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (scenario_id),
    KEY ix_scenario_input_hash (input_hash),
    CONSTRAINT fk_scenario_calculation FOREIGN KEY (calculation_snapshot_id) REFERENCES calculation_snapshots (snapshot_id),
    CONSTRAINT fk_scenario_portfolio FOREIGN KEY (portfolio_snapshot_id) REFERENCES portfolio_snapshots (snapshot_id),
    CONSTRAINT fk_scenario_decision FOREIGN KEY (decision_id, decision_version) REFERENCES decision_versions (decision_id, version),
    CONSTRAINT fk_scenario_evidence FOREIGN KEY (source_evidence_id) REFERENCES evidence (evidence_id)
) ENGINE=InnoDB;
