package org.jack.backend.report;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import org.jack.backend.workflow.WorkflowData.Run;

@Mapper
public interface HistoryMapper {
    record Head(String decisionId,String body,LocalDateTime createdAt,LocalDateTime updatedAt,String latestRunId) { }
    @Select("""
            <script>SELECT d.decision_id AS decisionId,v.content_json AS body,d.created_at AS createdAt,d.updated_at AS updatedAt,
              (SELECT r.run_id FROM research_runs r WHERE r.decision_id=d.decision_id AND r.owner_id=d.owner_id ORDER BY r.created_at DESC,r.run_id DESC LIMIT 1) AS latestRunId
            FROM decisions d JOIN decision_versions v ON d.decision_id=v.decision_id AND d.current_version=v.version
            WHERE d.owner_id=#{owner}
            <if test="time != null">AND (d.created_at &lt; #{time} OR (d.created_at=#{time} AND d.decision_id &lt; #{id}))</if>
            ORDER BY d.created_at DESC,d.decision_id DESC LIMIT #{limit}</script>
            """)
    List<Head> decisions(@Param("owner") String owner,@Param("time") LocalDateTime time,@Param("id") String id,@Param("limit") int limit);
    @Select("""
            <script>SELECT run_id AS runId FROM research_runs WHERE owner_id=#{owner} AND decision_id=#{decision}
            <if test="time != null">AND (created_at &lt; #{time} OR (created_at=#{time} AND run_id &lt; #{id}))</if>
            ORDER BY created_at DESC,run_id DESC LIMIT #{limit}</script>
            """)
    List<String> runs(@Param("owner") String owner,@Param("decision") String decision,@Param("time") LocalDateTime time,@Param("id") String id,@Param("limit") int limit);
}
