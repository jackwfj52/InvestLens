CREATE TABLE research_run_inputs (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    scenario_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_research_input_run FOREIGN KEY(run_id) REFERENCES research_runs(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_research_input_scenario FOREIGN KEY(scenario_id) REFERENCES scenario_results(scenario_id)
) ENGINE=InnoDB;
CREATE TABLE research_snapshots (
    snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    snapshot_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    CONSTRAINT fk_frozen_research_run FOREIGN KEY(run_id) REFERENCES research_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE research_evidence (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(run_id,evidence_id),
    CONSTRAINT fk_research_evidence_run FOREIGN KEY(run_id) REFERENCES research_runs(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_research_evidence_record FOREIGN KEY(evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;
