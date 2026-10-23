package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.infrastructure.resilience.*;
import org.jack.backend.report.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"spring.data.redis.port=1","spring.data.redis.connect-timeout=50ms","spring.data.redis.timeout=50ms"})
class CacheOutageTests extends ResearchPipelineSupport {
    @Autowired OptionalRedis cache;
    @Autowired ExternalAccountBudget account;
    @Autowired ReportService reports;
    @Test void unavailableRedisKeepsReadinessReportPublicationAndDurableReadbackWorking() throws Exception {
        assertThat(cache.available()).isFalse();assertThat(cache.get("investlens:test:missing")).isNull();
        var run=scenarioRun();for(int index=0;index<7;index++){var claim=claims.claimNext();assertThat(claim).isNotNull();checkpoints.save(claim,handler.execute(claim));}
        var report=reports.get(run.runId());assertThat(report.get("dataMode").asString()).isEqualTo("SYNTHETIC");assertThat(report.get("status").asString()).isEqualTo("PARTIAL");
        assertThat(reports.get(run.runId())).isEqualTo(report);assertThat(send("GET","/actuator/health/readiness",null).statusCode()).isEqualTo(200);
        assertThat(send("GET","/api/system/resilience",null).body()).contains("UNAVAILABLE","MYSQL");verifyNoInteractions(provider);
    }
    @Test void concurrentAccountQuotaIsDurableAndDoesNotDependOnRedis() throws Exception {
        String scope="g36_"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<Boolean>>();
            for(int index=0;index<8;index++)tasks.add(pool.submit(()->{start.await();try{account.take(scope,3);return true;}catch(BusinessException blocked){assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);return false;}}));
            start.countDown();int allowed=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))allowed++;
            assertThat(allowed).isEqualTo(3);assertThat(cache.available()).isFalse();assertThatThrownBy(()->account.take(scope,3)).isInstanceOf(BusinessException.class);
        }finally{pool.shutdownNow();jdbc.update("DELETE FROM external_account_budgets WHERE scope_hash=?",org.jack.backend.common.json.CanonicalJson.sha256(json.valueToTree(scope)));}
    }
}
