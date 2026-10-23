-- V4 is already applied: strengthen the policy relation without rewriting its checksum.
ALTER TABLE evidence ADD UNIQUE KEY uk_evidence_policy (evidence_id, storage_policy);
ALTER TABLE evidence_payloads DROP FOREIGN KEY fk_evidence_payload;
ALTER TABLE evidence_payloads ADD CONSTRAINT fk_evidence_payload_policy
    FOREIGN KEY (evidence_id, storage_policy) REFERENCES evidence (evidence_id, storage_policy);
