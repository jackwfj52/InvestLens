CREATE TABLE external_account_budgets (
    owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_at DATETIME(3) NOT NULL,
    used INT NOT NULL DEFAULT 0,
    PRIMARY KEY(owner_id,scope_hash,bucket_at),
    CONSTRAINT ck_account_used CHECK(used >= 0)
) ENGINE=InnoDB;
