package org.jack.backend.report;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ReportMapper {
    @Insert("INSERT INTO reports(report_id,run_id,content_json,figures_json,published_at) VALUES(#{report},#{run},#{body},#{figures},#{now})")
    void insert(@Param("report") String report,@Param("run") String run,@Param("body") String body,@Param("figures") String figures,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO verdicts(report_id,content_json) VALUES(#{report},#{body})")
    void verdict(@Param("report") String report,@Param("body") String body);
    @Insert("INSERT INTO evidence_links(report_id,evidence_id) VALUES(#{report},#{evidence})")
    void link(@Param("report") String report,@Param("evidence") String evidence);
    @Update("""
            UPDATE research_runs SET report_id=#{report},status=#{status},finished_at=#{now},error_code=NULL,
                revision=revision+1,event_sequence=event_sequence+1
            WHERE run_id=#{run} AND owner_id=#{owner} AND status='RUNNING' AND revision=#{revision}
              AND deadline_at > #{now} AND report_id IS NULL
            """)
    int finish(@Param("run") String run,@Param("owner") String owner,@Param("revision") long revision,@Param("report") String report,@Param("status") String status,@Param("now") LocalDateTime now);
    @Select("SELECT p.content_json FROM reports p JOIN research_runs r ON r.run_id=p.run_id AND r.report_id=p.report_id WHERE p.run_id=#{run} AND r.owner_id=#{owner} AND r.status IN ('COMPLETED','PARTIAL')")
    String get(@Param("run") String run,@Param("owner") String owner);
    @Select("SELECT p.figures_json FROM reports p JOIN research_runs r ON r.run_id=p.run_id AND r.report_id=p.report_id WHERE p.run_id=#{run} AND r.owner_id=#{owner} AND r.status IN ('COMPLETED','PARTIAL')")
    String figures(@Param("run") String run,@Param("owner") String owner);
}
