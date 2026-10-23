package org.jack.backend.evidence;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface EvidenceMapper {
    @Insert("INSERT INTO evidence (evidence_id, owner_id, storage_policy, content_json, created_at) VALUES (#{id}, #{owner}, #{policy}, #{content}, #{now})")
    void insertEvidence(@Param("id") String id, @Param("owner") String owner, @Param("policy") String policy,
                        @Param("content") String content, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO evidence_payloads (evidence_id, storage_policy, content_json) SELECT evidence_id, storage_policy, #{content} FROM evidence WHERE evidence_id = #{id} AND storage_policy = 'PAYLOAD_ALLOWED'")
    int insertPayload(@Param("id") String id, @Param("content") String content);

    @Insert("INSERT INTO calculation_snapshots (snapshot_id, owner_id, portfolio_snapshot_id, input_hash, content_json, created_at) VALUES (#{id}, #{owner}, #{portfolio}, #{hash}, #{content}, #{now})")
    void insertSnapshot(@Param("id") String id, @Param("owner") String owner, @Param("portfolio") String portfolio,
                        @Param("hash") String hash, @Param("content") String content, @Param("now") LocalDateTime now);

    @Select("SELECT content_json FROM evidence WHERE evidence_id = #{id} AND owner_id = #{owner}")
    String evidence(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT p.content_json FROM evidence_payloads p JOIN evidence e ON e.evidence_id = p.evidence_id WHERE p.evidence_id = #{id} AND e.owner_id = #{owner}")
    String payload(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT content_json FROM calculation_snapshots WHERE snapshot_id = #{id} AND owner_id = #{owner}")
    String snapshot(@Param("id") String id, @Param("owner") String owner);
}
