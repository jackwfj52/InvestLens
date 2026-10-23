package org.jack.backend.scenario;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ScenarioMapper {
    @Insert("""
            INSERT INTO scenario_results (scenario_id, owner_id, calculation_snapshot_id, portfolio_snapshot_id,
                decision_id, decision_version, source_evidence_id, input_hash, content_json, created_at)
            VALUES (#{id}, #{owner}, #{snapshot}, #{portfolio}, #{decision}, #{version}, #{evidence}, #{hash}, #{content}, #{now})
            """)
    void insert(@Param("id") String id, @Param("owner") String owner, @Param("snapshot") String snapshot,
                @Param("portfolio") String portfolio, @Param("decision") String decision, @Param("version") int version,
                @Param("evidence") String evidence, @Param("hash") String hash, @Param("content") String content, @Param("now") LocalDateTime now);
    @Select("SELECT content_json FROM scenario_results WHERE scenario_id = #{id} AND owner_id = #{owner}")
    String find(@Param("id") String id, @Param("owner") String owner);
}
