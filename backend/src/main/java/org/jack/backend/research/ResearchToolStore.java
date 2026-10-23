package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.evidence.*;
import org.jack.backend.market.*;
import org.jack.backend.quant.*;
import org.jack.backend.scenario.*;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.jack.backend.evidence.EvidenceData.Gap;
import static org.jack.backend.workflow.WorkflowClaimService.Claim;

@Service
public class ResearchToolStore {
    private final WorkflowClaimService claims;
    private final ResearchMapper mapper;
    private final EvidenceService evidence;
    private final MetricService metrics;
    private final ScenarioService scenarios;
    private final ObjectMapper json;
    private final WorkflowStore workflow;
    public ResearchToolStore(WorkflowClaimService claims,ResearchMapper mapper,EvidenceService evidence,MetricService metrics,ScenarioService scenarios,ObjectMapper json,WorkflowStore workflow){
        this.claims=claims;this.mapper=mapper;this.evidence=evidence;this.metrics=metrics;this.scenarios=scenarios;this.json=json;
        this.workflow=workflow;
    }
    public ResearchData.Tools existing(String run,String hash) {
        String body=mapper.tools(run);if(body==null)return null;
        var result=json.readValue(body,ResearchData.Tools.class);
        if(!result.planHash().equals(hash))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        return result;
    }
    @Transactional
    public ResearchData.Tools save(Claim claim,String hash,List<MarketData.Query> queries,Map<MarketData.Query,MarketQuality.Result> collected,
            String scenarioId,boolean calculate,List<Gap> failures) {
        claims.requireLive(claim);var prior=existing(claim.run().runId(),hash);if(prior!=null)return prior;
        var gaps=new ArrayList<>(failures);String calculationId;String bundleId=null;
        if(scenarioId!=null) {
            var scenario=scenarios.get(scenarioId);
            if(!scenario.decisionRef().decisionId().equals(claim.run().decisionId()) || scenario.decisionRef().version()!=claim.run().decisionVersion()
                    || !scenario.portfolioRef().snapshotId().equals(claim.run().portfolioSnapshotId()) || scenario.dataMode()!=claim.run().dataMode())throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            calculationId=scenario.calculationSnapshotId();
        } else {
            var snapshot=evidence.freezeCollected(claim.run().portfolioSnapshotId(),queries,claim.run().dataMode(),collected);
            calculationId=snapshot.snapshotId();gaps.addAll(snapshot.gaps());gaps.add(new Gap("SCENARIO_INPUT_MISSING",null,"尚未提供显式价格冲击情景"));
        }
        var snapshot=evidence.snapshot(calculationId);
        if(calculate && snapshot.replayable() && !snapshot.marketInputs().isEmpty()) {
            var bundle=metrics.calculate(new MetricData.Create("1.0.0",calculationId));bundleId=bundle.bundleId();
            if(bundle.metrics().stream().anyMatch(metric->metric.status()!=MetricData.Status.CALCULABLE))gaps.add(new Gap("HISTORICAL_METRICS_INCOMPLETE",bundleId,"部分历史指标样本不足"));
        } else gaps.add(new Gap("HISTORICAL_RISK_MISSING",null,"没有可复用的合格历史风险指标"));
        var result=new ResearchData.Tools(hash,calculationId,bundleId,scenarioId,gaps);
        workflow.linkSnapshot(claim.run().runId(),calculationId);
        if(bundleId!=null)workflow.linkBundle(claim.run().runId(),bundleId);
        mapper.insertTools(claim.run().runId(),hash,json.writeValueAsString(result));return result;
    }
}
