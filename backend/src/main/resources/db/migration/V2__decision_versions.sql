CREATE TABLE decisions (
    decision_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_version INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (decision_id),
    KEY ix_decisions_owner_created (owner_id, created_at, decision_id),
    CONSTRAINT ck_decisions_version CHECK (current_version >= 1)
) ENGINE=InnoDB;

CREATE TABLE decision_versions (
    decision_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL,
    draft_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (decision_id, version),
    CONSTRAINT fk_decision_versions_decision FOREIGN KEY (decision_id)
        REFERENCES decisions (decision_id),
    CONSTRAINT ck_decision_versions_version CHECK (version >= 1)
) ENGINE=InnoDB;
