package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.jack.backend.workflow.CallBudgetData.*;
import static org.jack.backend.workflow.WorkflowClaimService.Claim;

@Service
public class CallBudgetService {
    private final WorkflowClaimService claims;
    private final WorkflowStore store;
    private final CallBudgetMapper mapper;
    private final PortfolioService portfolios;
    private final PortfolioInstrumentCatalog instruments;
    private final Clock clock;
    public CallBudgetService(WorkflowClaimService claims,WorkflowStore store,CallBudgetMapper mapper,PortfolioService portfolios,PortfolioInstrumentCatalog instruments,Clock clock){
        this.claims=claims;this.store=store;this.mapper=mapper;this.portfolios=portfolios;this.instruments=instruments;this.clock=clock;
    }
    @Transactional
    public Permit reserve(Claim claim,Request request) {
        claims.requireLive(claim);
        if(request==null || request.kind()==null || request.purpose()==null || (request.purpose()==Purpose.SCHEMA_REPAIR && request.kind()!=Kind.MODEL))throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        var run=store.get(claim.run().runId());
        if(request.kind()==Kind.DATA) {
            if(request.instrumentId()==null || request.start()==null || request.end()==null)throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            MarketWindow.validate(new MarketData.Query(request.instrumentId(),request.start(),request.end()),clock);
            var p=portfolios.getSnapshot(run.portfolioSnapshotId());
            if(p.positions().stream().noneMatch(position->position.instrumentId().equals(request.instrumentId()))
                    || instruments.require(request.instrumentId(),run.dataMode(),"instrumentId").kind()==PortfolioInstrumentCatalog.Kind.CASH)throw new BusinessException(ErrorCode.UNSUPPORTED_INSTRUMENT);
            Window window=mapper.window(run.runId(),request.instrumentId());
            if(window!=null && (!window.start().equals(request.start()) || !window.end().equals(request.end())))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            if(window==null){if(mapper.windows(run.runId())>=3)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);mapper.insertWindow(run.runId(),request);}
        } else if(request.instrumentId()!=null || request.start()!=null || request.end()!=null)throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        LocalDateTime now=now();
        long remaining=Math.min(Duration.between(now,run.deadlineAt()).toMillis(),Duration.between(now,store.step(run.runId(),claim.step().stepKey()).leaseUntil()).toMillis());
        long timeout=Math.min(remaining,request.kind()==Kind.DATA?8000:30000);
        if(timeout<=0)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        if(mapper.increment(run.runId(),run.revision(),request.kind()==Kind.DATA?1:0,request.kind()==Kind.MODEL?1:0,
                request.purpose()==Purpose.SCHEMA_REPAIR?1:0,request.purpose()==Purpose.RETRY?1:0)!=1)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        Permit permit=new Permit("call_"+UUID.randomUUID(),run.runId(),claim.step().stepId(),claim.step().executionVersion(),request,
                now,now.plusNanos(timeout*1_000_000),timeout,RunDtos.remaining(store.get(run.runId()),clock));
        mapper.insert(permit);return permit;
    }
    /** Only the owned attempt fact may finish late; this never changes Run/Step/output or releases quota. */
    @Transactional
    public boolean finish(Permit permit,String errorCode) {
        var attempt=mapper.attempt(permit.callId());
        if(attempt==null || !attempt.runId().equals(permit.runId()) || !attempt.stepId().equals(permit.stepId()) || attempt.executionVersion()!=permit.executionVersion())throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if(!"STARTED".equals(attempt.status()))return !"TIMED_OUT".equals(attempt.status());
        LocalDateTime now=now();boolean timedOut=!now.isBefore(attempt.expiresAt());
        mapper.finish(permit,timedOut?"TIMED_OUT":errorCode==null?"SUCCEEDED":"FAILED",timedOut?"UPSTREAM_TIMEOUT":errorCode,now);
        return !timedOut;
    }
    @Transactional
    public void beforeDispatch(Claim claim,Permit permit) {
        claims.requireLive(claim);
        if(!now().isBefore(permit.expiresAt()))throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
}
