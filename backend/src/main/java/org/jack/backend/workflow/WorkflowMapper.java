package org.jack.backend.workflow;

import org.apache.ibatis.annotations.*;
import java.util.List;
import static org.jack.backend.workflow.WorkflowData.*;

@Mapper
public interface WorkflowMapper {
    @Select("""
            SELECT r.run_id FROM research_runs r
            WHERE r.owner_id=#{owner} AND r.status IN ('QUEUED','RUNNING')
              AND (r.deadline_at <= #{now} OR EXISTS (SELECT 1 FROM run_steps s WHERE s.run_id=r.run_id
                AND (s.status='PENDING' OR (s.status='RUNNING' AND s.lease_until <= #{now}))))
              AND NOT EXISTS (SELECT 1 FROM run_steps s WHERE s.run_id=r.run_id AND s.status='RUNNING' AND s.lease_until > #{now})
            ORDER BY r.created_at,r.run_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    String claimable(@Param("owner") String owner, @Param("now") java.time.LocalDateTime now);
    @Select("SELECT " + STEP_COLUMNS + " FROM run_steps WHERE run_id=#{id} AND (status='PENDING' OR (status='RUNNING' AND lease_until <= #{now})) ORDER BY created_at,step_id LIMIT 1")
    Step claimableStep(@Param("id") String id, @Param("now") java.time.LocalDateTime now);
    record KeyMapping(String requestHash, String runId) { }
    @Insert("INSERT INTO idempotency_keys (owner_id,idempotency_key,request_hash) VALUES (#{owner},#{key},#{hash}) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key")
    void reserveKey(@Param("owner") String owner,@Param("key") String key,@Param("hash") String hash);
    @Select("SELECT request_hash AS requestHash,run_id AS runId FROM idempotency_keys WHERE owner_id=#{owner} AND idempotency_key=#{key} FOR UPDATE")
    KeyMapping key(@Param("owner") String owner,@Param("key") String key);
    @Update("UPDATE idempotency_keys SET run_id=#{run} WHERE owner_id=#{owner} AND idempotency_key=#{key} AND run_id IS NULL")
    int bindKey(@Param("owner") String owner,@Param("key") String key,@Param("run") String run);
    String RUN_COLUMNS = "run_id AS runId, decision_id AS decisionId, decision_version AS decisionVersion, portfolio_snapshot_id AS portfolioSnapshotId, portfolio_version AS portfolioVersion, status, stage, data_mode AS dataMode, data_quality AS dataQuality, research_snapshot_id AS researchSnapshotId, snapshot_hash AS snapshotHash, report_id AS reportId, error_code AS errorCode, created_at AS createdAt, started_at AS startedAt, finished_at AS finishedAt, deadline_at AS deadlineAt, data_attempts AS dataAttempts, model_attempts AS modelAttempts, schema_repairs AS schemaRepairs, retry_count AS retryCount, revision, event_sequence AS eventSequence";
    String STEP_COLUMNS = "step_id AS stepId, run_id AS runId, step_key AS stepKey, status, attempt, input_hash AS inputHash, output_ref AS outputRef, lease_until AS leaseUntil, execution_version AS executionVersion, error_code AS errorCode, created_at AS createdAt, updated_at AS updatedAt";
    @Insert("""
            INSERT INTO research_runs (run_id, owner_id, decision_id, decision_version, portfolio_snapshot_id,
                portfolio_version, status, data_mode, created_at, deadline_at)
            VALUES (#{r.runId}, #{owner}, #{r.decisionId}, #{r.decisionVersion}, #{r.portfolioSnapshotId},
                #{r.portfolioVersion}, #{r.status}, #{r.dataMode}, #{r.createdAt}, #{r.deadlineAt})
            """)
    void insertRun(@Param("r") Run run, @Param("owner") String owner);
    @Select("SELECT " + RUN_COLUMNS + " FROM research_runs WHERE run_id = #{id} AND owner_id = #{owner}")
    Run run(@Param("id") String id, @Param("owner") String owner);
    @Select("SELECT " + RUN_COLUMNS + " FROM research_runs WHERE run_id = #{id} AND owner_id = #{owner} FOR UPDATE")
    Run lockRun(@Param("id") String id, @Param("owner") String owner);
    @Update("""
            UPDATE research_runs SET status = #{r.status}, stage = #{r.stage}, error_code = #{r.errorCode},
                started_at = #{r.startedAt}, finished_at = #{r.finishedAt}, revision = #{r.revision}, event_sequence = #{r.eventSequence}
            WHERE run_id = #{r.runId} AND owner_id = #{owner} AND revision = #{expected}
              AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')
            """)
    int updateRun(@Param("r") Run run, @Param("owner") String owner, @Param("expected") long expected);
    @Insert("""
            INSERT INTO run_steps (step_id, run_id, step_key, status, input_hash, created_at, updated_at)
            VALUES (#{s.stepId}, #{s.runId}, #{s.stepKey}, #{s.status}, #{s.inputHash}, #{s.createdAt}, #{s.updatedAt})
            """)
    void insertStep(@Param("s") Step step);
    @Select("SELECT " + STEP_COLUMNS + " FROM run_steps WHERE run_id = #{id} AND step_key = #{key}")
    Step step(@Param("id") String id, @Param("key") StepKey key);
    @Update("""
            UPDATE run_steps SET status = #{s.status}, attempt = #{s.attempt}, output_ref = #{s.outputRef},
                lease_until = #{s.leaseUntil}, execution_version = #{s.executionVersion}, error_code = #{s.errorCode}, updated_at = #{s.updatedAt}
            WHERE step_id = #{s.stepId} AND status = #{previous} AND execution_version = #{token}
              AND status NOT IN ('SUCCEEDED','SKIPPED')
              AND EXISTS (SELECT 1 FROM research_runs r WHERE r.run_id = run_steps.run_id AND r.status IN ('RUNNING','QUEUED'))
            """)
    int updateStep(@Param("s") Step step, @Param("previous") StepStatus previous, @Param("token") long token);
    @Insert("INSERT INTO run_events (event_id, run_id, sequence, type, stage, status, created_at, message) VALUES (#{eventId}, #{runId}, #{sequence}, #{type}, #{stage}, #{status}, #{createdAt}, #{message})")
    void insertEvent(Event event);
    @Select("SELECT event_id AS eventId, run_id AS runId, sequence, type, stage, status, created_at AS createdAt, message FROM run_events WHERE run_id = #{id} ORDER BY sequence")
    List<Event> events(@Param("id") String id);
    @Insert("INSERT INTO run_calculation_inputs (run_id, calculation_snapshot_id, portfolio_snapshot_id) VALUES (#{run}, #{snapshot}, #{portfolio})")
    void linkSnapshot(@Param("run") String run, @Param("snapshot") String snapshot, @Param("portfolio") String portfolio);
    @Insert("INSERT INTO run_metric_bundles (run_id, bundle_id, calculation_snapshot_id) VALUES (#{run}, #{bundle}, #{snapshot})")
    void linkBundle(@Param("run") String run, @Param("bundle") String bundle, @Param("snapshot") String snapshot);
}
