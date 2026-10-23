ALTER TABLE tool_calls
    ADD COLUMN model_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN finish_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN prompt_tokens BIGINT NULL,
    ADD COLUMN completion_tokens BIGINT NULL,
    ADD COLUMN total_tokens BIGINT NULL;
