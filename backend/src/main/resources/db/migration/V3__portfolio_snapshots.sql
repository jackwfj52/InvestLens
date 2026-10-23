CREATE TABLE portfolios (
    portfolio_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    current_version INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (portfolio_id),
    KEY ix_portfolios_owner_created (owner_id, created_at, portfolio_id),
    CONSTRAINT ck_portfolios_version CHECK (current_version >= 1)
) ENGINE=InnoDB;

CREATE TABLE portfolio_snapshots (
    snapshot_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    portfolio_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL,
    content_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_portfolio_snapshots_version (portfolio_id, version),
    CONSTRAINT fk_portfolio_snapshots_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolios (portfolio_id),
    CONSTRAINT ck_portfolio_snapshots_version CHECK (version >= 1)
) ENGINE=InnoDB;
