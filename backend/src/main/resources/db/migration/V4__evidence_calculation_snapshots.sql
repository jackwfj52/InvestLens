CREATE TABLE evidence (
    evidence_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_policy VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (evidence_id),
    CONSTRAINT ck_evidence_storage CHECK (storage_policy IN ('PAYLOAD_ALLOWED', 'METADATA_ONLY')),
    CONSTRAINT ck_evidence_policy_json CHECK (JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.storagePolicy')) = storage_policy),
    CONSTRAINT ck_evidence_metadata CHECK (storage_policy = 'PAYLOAD_ALLOWED' OR
        (JSON_TYPE(JSON_EXTRACT(content_json, '$.payloadRef')) = 'NULL'
         AND JSON_TYPE(JSON_EXTRACT(content_json, '$.payloadHash')) = 'NULL'))
) ENGINE=InnoDB;

CREATE TABLE evidence_payloads (
    evidence_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_policy VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    PRIMARY KEY (evidence_id),
    CONSTRAINT fk_evidence_payload FOREIGN KEY (evidence_id) REFERENCES evidence (evidence_id),
    CONSTRAINT ck_payload_policy CHECK (storage_policy = 'PAYLOAD_ALLOWED')
) ENGINE=InnoDB;

CREATE TABLE calculation_snapshots (
    snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    portfolio_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    KEY ix_calculation_input_hash (input_hash),
    CONSTRAINT fk_calculation_portfolio FOREIGN KEY (portfolio_snapshot_id) REFERENCES portfolio_snapshots (snapshot_id)
) ENGINE=InnoDB;
