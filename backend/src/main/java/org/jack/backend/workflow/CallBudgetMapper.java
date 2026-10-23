package org.jack.backend.workflow;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import static org.jack.backend.workflow.CallBudgetData.*;

@Mapper
public interface CallBudgetMapper {
    @Update("UPDATE tool_calls SET model_name=#{r.model},finish_reason=#{r.finishReason},prompt_tokens=#{r.usage.promptTokens},completion_tokens=#{r.usage.completionTokens},total_tokens=#{r.usage.totalTokens} WHERE call_id=#{p.callId} AND run_id=#{p.runId} AND step_id=#{p.stepId} AND execution_version=#{p.executionVersion} AND kind='MODEL'")
    int usage(@Param("p") Permit permit,@Param("r") org.jack.backend.llm.LlmClient.Reply reply);
    record Attempt(String runId,String stepId,long executionVersion,String status,LocalDateTime expiresAt) { }
    @Select("SELECT run_id AS runId,step_id AS stepId,execution_version AS executionVersion,status,expires_at AS expiresAt FROM tool_calls WHERE call_id=#{id} FOR UPDATE")
    Attempt attempt(String id);
    @Select("SELECT instrument_id AS instrumentId,window_start AS start,window_end AS end FROM run_data_windows WHERE run_id=#{run} AND instrument_id=#{instrument}")
    Window window(@Param("run") String run,@Param("instrument") String instrument);
    @Select("SELECT COUNT(*) FROM run_data_windows WHERE run_id=#{run}") int windows(String run);
    @Insert("INSERT INTO run_data_windows (run_id,instrument_id,window_start,window_end) VALUES (#{run},#{q.instrumentId},#{q.start},#{q.end})")
    void insertWindow(@Param("run") String run,@Param("q") Request request);
    @Update("""
            UPDATE research_runs SET data_attempts=data_attempts+#{data}, model_attempts=model_attempts+#{model},
                schema_repairs=schema_repairs+#{repair},retry_count=retry_count+#{retry},revision=revision+1
            WHERE run_id=#{run} AND revision=#{revision} AND status='RUNNING'
                AND data_attempts+#{data}<=8 AND model_attempts+#{model}<=5 AND schema_repairs+#{repair}<=1
                AND model_attempts+#{model}-schema_repairs-#{repair}<=4 AND retry_count+#{retry}<=2
            """)
    int increment(@Param("run") String run,@Param("revision") long revision,@Param("data") int data,@Param("model") int model,@Param("repair") int repair,@Param("retry") int retry);
    @Insert("""
            INSERT INTO tool_calls (call_id,run_id,step_id,execution_version,kind,purpose,instrument_id,window_start,window_end,
                status,timeout_millis,started_at,expires_at)
            VALUES (#{callId},#{runId},#{stepId},#{executionVersion},#{request.kind},#{request.purpose},#{request.instrumentId},#{request.start},#{request.end},
                'STARTED',#{timeoutMillis},#{startedAt},#{expiresAt})
            """)
    void insert(Permit permit);
    @Update("UPDATE tool_calls SET status=#{status},error_code=#{code},finished_at=#{now} WHERE call_id=#{p.callId} AND run_id=#{p.runId} AND step_id=#{p.stepId} AND execution_version=#{p.executionVersion} AND status='STARTED'")
    int finish(@Param("p") Permit permit,@Param("status") String status,@Param("code") String code,@Param("now") LocalDateTime now);
}
