CREATE TABLE metric_bundles (
    bundle_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (bundle_id),
    KEY ix_metric_bundles_input (input_hash),
    CONSTRAINT fk_metric_bundle_snapshot FOREIGN KEY (calculation_snapshot_id) REFERENCES calculation_snapshots (snapshot_id)
) ENGINE=InnoDB;
