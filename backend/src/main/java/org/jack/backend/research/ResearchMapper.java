package org.jack.backend.research;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ResearchMapper {
    @Select("SELECT evidence_id FROM research_evidence WHERE run_id=#{run} ORDER BY evidence_id")
    java.util.List<String> evidenceIds(String run);
    @Select("SELECT COUNT(*) FROM tool_calls c JOIN run_steps s ON s.step_id=c.step_id WHERE c.run_id=#{run} AND s.step_key='PLAN' AND c.kind='MODEL' AND c.status='SUCCEEDED'")
    int successfulPlannerCalls(String run);
    @Insert("INSERT INTO research_run_inputs(run_id,scenario_id) VALUES(#{run},#{scenario})")
    void bindScenario(@Param("run") String run,@Param("scenario") String scenario);
    @Select("SELECT i.scenario_id FROM research_run_inputs i JOIN research_runs r ON r.run_id=i.run_id WHERE i.run_id=#{run} AND r.owner_id=#{owner}")
    String scenario(@Param("run") String run,@Param("owner") String owner);
    @Insert("INSERT INTO research_snapshots(snapshot_id,run_id,snapshot_hash,content_json) VALUES(#{id},#{run},#{hash},#{body})")
    void insertFrozen(@Param("id") String id,@Param("run") String run,@Param("hash") String hash,@Param("body") String body);
    @Select("SELECT s.content_json FROM research_snapshots s JOIN research_runs r ON r.run_id=s.run_id WHERE s.run_id=#{run} AND r.owner_id=#{owner}")
    String frozen(@Param("run") String run,@Param("owner") String owner);
    @Insert("INSERT INTO research_evidence(run_id,evidence_id) VALUES(#{run},#{id})")
    void linkEvidence(@Param("run") String run,@Param("id") String id);
    @Update("UPDATE research_runs SET research_snapshot_id=#{id},snapshot_hash=#{hash},data_quality=#{quality},revision=revision+1 WHERE run_id=#{run} AND status='RUNNING' AND research_snapshot_id IS NULL")
    int bindFrozen(@Param("run") String run,@Param("id") String id,@Param("hash") String hash,@Param("quality") String quality);
    @Select("SELECT content_json FROM research_tool_results WHERE run_id=#{run}") String tools(String run);
    @Insert("INSERT INTO research_tool_results(run_id,plan_hash,content_json) VALUES(#{run},#{hash},#{body})")
    void insertTools(@Param("run") String run,@Param("hash") String hash,@Param("body") String body);
}
