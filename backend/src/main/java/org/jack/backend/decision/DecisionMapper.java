package org.jack.backend.decision;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface DecisionMapper {
    @Insert("""
            INSERT INTO decisions (decision_id, owner_id, current_version, created_at, updated_at)
            VALUES (#{id}, #{owner}, 1, #{now}, #{now})
            """)
    int insertHead(@Param("id") String id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO decision_versions (decision_id, version, draft_id, content_json, created_at)
            VALUES (#{id}, #{version}, #{draftId}, #{json}, #{now})
            """)
    int insertVersion(@Param("id") String id, @Param("version") int version,
                      @Param("draftId") String draftId, @Param("json") String json, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE decisions SET current_version = current_version + 1, updated_at = #{now}
            WHERE decision_id = #{id} AND owner_id = #{owner} AND current_version = #{expected}
            """)
    int advance(@Param("id") String id, @Param("owner") String owner,
                @Param("expected") int expected, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM decisions WHERE decision_id = #{id} AND owner_id = #{owner}")
    int exists(@Param("id") String id, @Param("owner") String owner);

    @Select("""
            <script>
            SELECT v.content_json FROM decisions d JOIN decision_versions v ON v.decision_id = d.decision_id
            WHERE d.decision_id = #{id} AND d.owner_id = #{owner}
            <choose>
              <when test="version != null">AND v.version = #{version}</when>
              <otherwise>AND v.version = d.current_version</otherwise>
            </choose>
            </script>
            """)
    String find(@Param("id") String id, @Param("owner") String owner, @Param("version") Integer version);
}
