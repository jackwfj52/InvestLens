package org.jack.backend.quant;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface MetricMapper {
    @Insert("INSERT INTO metric_bundles (bundle_id, owner_id, calculation_snapshot_id, input_hash, content_json, created_at) VALUES (#{id}, #{owner}, #{snapshot}, #{hash}, #{content}, #{now})")
    void insert(@Param("id") String id, @Param("owner") String owner, @Param("snapshot") String snapshot,
                @Param("hash") String hash, @Param("content") String content, @Param("now") LocalDateTime now);
    @Select("SELECT content_json FROM metric_bundles WHERE bundle_id = #{id} AND owner_id = #{owner}")
    String find(@Param("id") String id, @Param("owner") String owner);
}
