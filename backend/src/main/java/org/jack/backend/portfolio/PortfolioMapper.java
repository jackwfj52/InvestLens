package org.jack.backend.portfolio;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface PortfolioMapper {
    @Insert("""
            INSERT INTO portfolios (portfolio_id, owner_id, name, current_version, created_at, updated_at)
            VALUES (#{id}, #{owner}, #{name}, 1, #{now}, #{now})
            """)
    int insertHead(@Param("id") String id, @Param("owner") String owner,
                   @Param("name") String name, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO portfolio_snapshots (snapshot_id, portfolio_id, version, content_hash, content_json, created_at)
            VALUES (#{snapshotId}, #{id}, #{version}, #{hash}, #{json}, #{now})
            """)
    int insertSnapshot(@Param("snapshotId") String snapshotId, @Param("id") String id,
                       @Param("version") int version, @Param("hash") String hash,
                       @Param("json") String json, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE portfolios SET current_version = current_version + 1, updated_at = #{now}
            WHERE portfolio_id = #{id} AND owner_id = #{owner} AND current_version = #{expected}
            """)
    int advance(@Param("id") String id, @Param("owner") String owner,
                @Param("expected") int expected, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT s.content_json FROM portfolios p JOIN portfolio_snapshots s ON s.portfolio_id = p.portfolio_id
            WHERE p.portfolio_id = #{id} AND p.owner_id = #{owner}
            <choose>
              <when test="version != null">AND s.version = #{version}</when>
              <otherwise>AND s.version = p.current_version</otherwise>
            </choose>
            </script>
            """)
    String find(@Param("id") String id, @Param("owner") String owner, @Param("version") Integer version);

    @Select("""
            SELECT s.content_json FROM portfolio_snapshots s JOIN portfolios p ON p.portfolio_id = s.portfolio_id
            WHERE s.snapshot_id = #{snapshotId} AND p.owner_id = #{owner}
            """)
    String findSnapshot(@Param("snapshotId") String snapshotId, @Param("owner") String owner);
}
