package org.jack.backend.infrastructure.resilience;

import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/resilience")
public class ResilienceController {
    public record Status(String cache,String durableStore,String callBudget,String message) { }
    private final OptionalRedis redis;
    public ResilienceController(OptionalRedis redis){this.redis=redis;}
    @GetMapping
    public ResponseEntity<ApiResponse<Status>> get() {
        boolean available=redis.available();
        return ApiResponses.ok(new Status(available?"AVAILABLE":"UNAVAILABLE","MYSQL","MYSQL",available?"缓存可用；历史事实和调用预算由数据库保存":"缓存不可用；历史仍从数据库读取，实时请求受持久预算保护，缓存模式不自动回源"));
    }
}
