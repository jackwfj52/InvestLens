package org.jack.backend.market;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.infrastructure.resilience.OptionalRedis;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.jack.backend.market.MarketData.*;

@Service
public class MarketHistoryCache {
    private final OptionalRedis redis;
    private final MarketDataProvider provider;
    private final MarketQualityService quality;
    private final CurrentOwner owner;
    private final ObjectMapper json;
    private final Clock clock;
    private final int seconds;
    private final ConcurrentMap<String,CompletableFuture<History>> flights=new ConcurrentHashMap<>();
    private final Semaphore slots=new Semaphore(8);
    public MarketHistoryCache(OptionalRedis redis,MarketDataProvider provider,MarketQualityService quality,CurrentOwner owner,ObjectMapper json,Clock clock,
            @Value("${investlens.market.sina.cache-seconds:0}") int seconds){this.redis=redis;this.provider=provider;this.quality=quality;this.owner=owner;this.json=json;this.clock=clock;this.seconds=seconds;}
    public History load(Query query,Candidate candidate,DataMode mode,long timeoutMillis) {
        if(timeoutMillis<=0)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(8000,timeoutMillis));
        if(mode==DataMode.REPLAY || mode==DataMode.SYNTHETIC)throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        boolean permitted="SINA".equals(candidate.instrument().provider()) && "QUALIFIED".equals(candidate.qualification()) && candidate.mainlandAccessVerified()
                && "ALLOWED".equals(candidate.permissions().localDisplay()) && "ALLOWED".equals(candidate.permissions().modelInput()) && "PAYLOAD_ALLOWED".equals(candidate.permissions().storagePolicy());
        String key="investlens:history:"+CanonicalJson.sha256(json.valueToTree(Map.of("owner",owner.id(),"query",query,"candidate",candidate,"quality",MarketQuality.VERSION)));
        if(mode==DataMode.CACHE) {
            if(!permitted || seconds<1 || seconds>900)throw new BusinessException(ErrorCode.DATA_UNUSABLE);
            String body=redis.get(key);
            try{History history=body==null?null:json.readValue(body,History.class);if(history!=null && cacheable(query,candidate,history))return history;}
            catch(RuntimeException invalid){throw new BusinessException(ErrorCode.DATA_UNUSABLE);}
            throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        }
        if(!permitted)return provider.history(query,timeoutMillis);
        var pending=new CompletableFuture<History>();var previous=flights.putIfAbsent(key,pending);
        if(previous!=null)try{return previous.get(Math.max(1,Math.min(8000,timeoutMillis)),TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);}
        catch(TimeoutException timeout){throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);}
        catch(ExecutionException failed){if(failed.getCause() instanceof BusinessException business)throw business;throw new BusinessException(ErrorCode.UPSTREAM_ERROR);}
        try {
            if(!slots.tryAcquire())throw new BusinessException(ErrorCode.RATE_LIMITED);
            try {
                var history=provider.history(query,timeoutMillis);
                if(seconds>0 && seconds<=900 && cacheable(query,candidate,history)) {
                    String body=json.writeValueAsString(history);if(body.length()<=262144 && deadline-System.nanoTime()>TimeUnit.MILLISECONDS.toNanos(300))redis.put(key,body,Duration.ofSeconds(seconds));
                }
                pending.complete(history);return history;
            }finally{slots.release();}
        }catch(RuntimeException failed){pending.completeExceptionally(failed);throw failed;}
        finally{flights.remove(key,pending);}
    }
    private boolean cacheable(Query query,Candidate candidate,History history) {
        MarketWindow.requireMatching(query,history,candidate);
        return "PAYLOAD_ALLOWED".equals(history.permissions().storagePolicy()) && quality.assess(history,clock.instant()).calculationAllowed();
    }
}
