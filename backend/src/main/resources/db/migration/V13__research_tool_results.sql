CREATE TABLE research_tool_results (
    run_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    plan_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json JSON NOT NULL,
    CONSTRAINT fk_research_tools_run FOREIGN KEY (run_id) REFERENCES research_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;
