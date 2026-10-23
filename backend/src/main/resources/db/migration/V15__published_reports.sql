CREATE TABLE reports (
    report_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    content_json JSON NOT NULL,
    figures_json JSON NOT NULL,
    published_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_report_run FOREIGN KEY (run_id) REFERENCES research_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE verdicts (
    report_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    content_json JSON NOT NULL,
    CONSTRAINT fk_verdict_report FOREIGN KEY (report_id) REFERENCES reports(report_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE evidence_links (
    report_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (report_id,evidence_id),
    CONSTRAINT fk_link_report FOREIGN KEY (report_id) REFERENCES reports(report_id) ON DELETE CASCADE,
    CONSTRAINT fk_link_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;

CREATE INDEX ix_decision_history ON decisions(owner_id,created_at,decision_id);
CREATE INDEX ix_decision_runs ON research_runs(owner_id,decision_id,created_at,run_id);
