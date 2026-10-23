package org.jack.backend.research;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ResearchMapper {
    @Select("SELECT content_json FROM research_tool_results WHERE run_id=#{run}") String tools(String run);
    @Insert("INSERT INTO research_tool_results(run_id,plan_hash,content_json) VALUES(#{run},#{hash},#{body})")
    void insertTools(@Param("run") String run,@Param("hash") String hash,@Param("body") String body);
}
