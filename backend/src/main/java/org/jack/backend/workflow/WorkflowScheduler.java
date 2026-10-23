package org.jack.backend.workflow;

import jakarta.annotation.PreDestroy;
import org.jack.backend.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="investlens.workflow.scheduler.enabled",havingValue="true",matchIfMissing=true)
public class WorkflowScheduler {
    private static final int WORKERS=2;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(WORKERS,WORKERS,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WORKERS),r->{Thread t=new Thread(r,"investlens-workflow");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore slots=new Semaphore(WORKERS);
    private final WorkflowClaimService claims;
    private final WorkflowHandler handler;
    private final WorkflowCheckpointService checkpoints;
    public WorkflowScheduler(WorkflowClaimService claims,WorkflowHandler handler,WorkflowCheckpointService checkpoints){this.claims=claims;this.handler=handler;this.checkpoints=checkpoints;}
    @Scheduled(fixedDelayString="${investlens.workflow.scheduler.delay-ms:500}")
    public void tick() { for(int i=0;i<WORKERS;i++)if(!submitOne())break; }
    public boolean submitOne() {
        if(!slots.tryAcquire())return false;
        try { executor.execute(()->{try{work();}finally{slots.release();}});return true; }
        catch(RejectedExecutionException rejected){slots.release();return false;}
    }
    public int inFlight(){return WORKERS-slots.availablePermits();}
    private void work() {
        WorkflowClaimService.Claim claim=null;
        try {
            claim=claims.claimNext();if(claim==null)return;
            var result=handler.execute(claim);
            if(!Thread.currentThread().isInterrupted())checkpoints.save(claim,result);
        } catch(Exception failure) {
            if(claim==null || Thread.currentThread().isInterrupted())return;
            String code=failure instanceof BusinessException business?business.getErrorCode().name():"INTERNAL_ERROR";
            try{checkpoints.fail(claim,code);}catch(BusinessException stale){ /* Expired or superseded workers cannot write. */ }
        }
    }
    @PreDestroy public void stop(){executor.shutdownNow();}
    @Configuration @EnableScheduling static class Scheduling { }
}
