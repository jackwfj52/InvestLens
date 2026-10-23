package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.portfolio.PortfolioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class RunCreationService {
    private final WorkflowMapper mapper;
    private final WorkflowStore store;
    private final CurrentOwner owner;
    private final ObjectMapper json;
    private final PortfolioService portfolios;
    public RunCreationService(WorkflowMapper mapper, WorkflowStore store, CurrentOwner owner, ObjectMapper json, PortfolioService portfolios) {
        this.mapper=mapper; this.store=store; this.owner=owner; this.json=json; this.portfolios=portfolios;
    }
    @Transactional
    public Run create(String decisionId, String key, RunDtos.Create request) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        String hash = CanonicalJson.sha256(json.valueToTree(Map.of("schemaVersion",request.schemaVersion(),"ownerId",owner.id(),
                "decisionId",decisionId,"decisionVersion",request.decisionVersion(),"portfolioSnapshotId",request.portfolioSnapshotId(),
                "portfolioVersion",request.portfolioVersion(),"dataMode",request.dataMode().name())));
        // The unique insert locks even when the row already exists; no catch of a rollback-only duplicate insert.
        mapper.reserveKey(owner.id(),key,hash);
        var mapping = mapper.key(owner.id(),key);
        if (!hash.equals(mapping.requestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        if (mapping.runId()!=null) return store.get(mapping.runId());
        var portfolio = portfolios.getSnapshot(request.portfolioSnapshotId());
        if (portfolio.version()!=request.portfolioVersion() || portfolio.dataMode()!=request.dataMode()) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        Run run=store.createModel(decisionId,request.decisionVersion(),request.portfolioSnapshotId());
        store.addStep(run.runId(),StepKey.PLAN,hash);
        Run queued=store.transition(run.runId(),0,RunStatus.QUEUED,null,null);
        mapper.bindKey(owner.id(),key,run.runId());
        return queued;
    }
}
