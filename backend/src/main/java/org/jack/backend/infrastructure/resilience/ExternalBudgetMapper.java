package org.jack.backend.infrastructure.resilience;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ExternalBudgetMapper {
    @Insert("INSERT INTO external_account_budgets(owner_id,scope_hash,bucket_at) VALUES(#{owner},#{scope},#{bucket}) ON DUPLICATE KEY UPDATE used=used")
    void ensure(@Param("owner") String owner,@Param("scope") String scope,@Param("bucket") LocalDateTime bucket);
    @Update("UPDATE external_account_budgets SET used=used+1 WHERE owner_id=#{owner} AND scope_hash=#{scope} AND bucket_at=#{bucket} AND used < #{limit}")
    int take(@Param("owner") String owner,@Param("scope") String scope,@Param("bucket") LocalDateTime bucket,@Param("limit") int limit);
    @Delete("DELETE FROM external_account_budgets WHERE owner_id=#{owner} AND scope_hash=#{scope} AND bucket_at < #{before}")
    void prune(@Param("owner") String owner,@Param("scope") String scope,@Param("before") LocalDateTime before);
}
