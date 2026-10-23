package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.evidence.*;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.jack.backend.quant.*;
import org.jack.backend.scenario.*;
import org.jack.backend.workflow.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.jack.backend.evidence.EvidenceData.*;
import static org.jack.backend.workflow.WorkflowClaimService.Claim;

@Service
public class ResearchFreezeService {
    private final ResearchMapper mapper;
    private final CurrentOwner owner;
    private final WorkflowClaimService claims;
    private final EvidenceService evidence;
    private final EvidenceMapper records;
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    private final PortfolioInstrumentCatalog catalog;
    private final MetricService metrics;
    private final MetricFactory factory;
    private final ScenarioService scenarios;
    private final ObjectMapper json;
    private final Clock clock;
    private final QuantPolicy quantPolicy;
    private final String model,disableThinking;
    public ResearchFreezeService(ResearchMapper mapper,CurrentOwner owner,WorkflowClaimService claims,EvidenceService evidence,EvidenceMapper records,
            DecisionService decisions,PortfolioService portfolios,PortfolioInstrumentCatalog catalog,MetricService metrics,MetricFactory factory,ScenarioService scenarios,ObjectMapper json,Clock clock,
            @Value("${LLM_MODEL:disabled}") String model,@Value("${LLM_DISABLE_THINKING:false}") String disableThinking,QuantPolicy quantPolicy){
        this.mapper=mapper;this.owner=owner;this.claims=claims;this.evidence=evidence;this.records=records;this.decisions=decisions;this.portfolios=portfolios;
        this.catalog=catalog;this.metrics=metrics;this.factory=factory;this.scenarios=scenarios;this.json=json;this.clock=clock;this.model=model;this.disableThinking=disableThinking;this.quantPolicy=quantPolicy;
    }
    public ResearchData.Frozen get(String run) {
        String body=mapper.frozen(run,owner.id());if(body==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var frozen=json.readValue(body,ResearchData.Frozen.class);var snapshot=(ObjectNode)frozen.snapshot().deepCopy();
        String expected=snapshot.remove("snapshotHash").asString();snapshot.remove("snapshotId");snapshot.remove("frozenAt");
        if(!run.equals(snapshot.get("runId").asString()) || !expected.equals(CanonicalJson.sha256(snapshot)) || frozen.inputContent()==null
                || !CanonicalJson.sha256(frozen.inputContent()).equals(frozen.metricBundle().get("inputHash").asString()))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        return frozen;
    }
    @Transactional
    public ResearchData.Frozen freeze(Claim claim,JsonNode plan) {
        claims.requireLive(claim);
        if(claim.step().stepKey()!=WorkflowData.StepKey.QUANT)throw new BusinessException(ErrorCode.INVALID_STATE);
        if(mapper.frozen(claim.run().runId(),owner.id())!=null)return get(claim.run().runId());
        var run=claim.run();String toolsBody=mapper.tools(run.runId());if(toolsBody==null)throw new BusinessException(ErrorCode.INVALID_STATE);
        var tools=json.readValue(toolsBody,ResearchData.Tools.class);
        if(!tools.planHash().equals(CanonicalJson.sha256(plan)))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        var decision=decisions.requireConfirmed(run.decisionId(),run.decisionVersion());var portfolio=portfolios.getSnapshot(run.portfolioSnapshotId());
        var calculation=evidence.snapshot(tools.calculationSnapshotId());
        if(!calculation.portfolioRef().snapshotId().equals(portfolio.snapshotId()) || calculation.dataMode()!=run.dataMode())throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        var sourceMetrics=new ArrayList<MetricData.Metric>();var sources=new TreeSet<String>();
        ScenarioData.Result scenario=tools.scenarioId()==null?null:scenarios.get(tools.scenarioId());
        if(tools.bundleId()!=null){var bundle=metrics.get(tools.bundleId());sourceMetrics.addAll(bundle.metrics());}
        if(scenario!=null){sourceMetrics.addAll(scenario.metrics());sources.add(scenario.sourceEvidenceRef());}
        calculation.marketInputs().forEach(input->sources.add(input.evidenceRef()));
        var bound=new ArrayList<Evidence>();var evidenceIds=new HashMap<String,String>();
        for(String sourceId:sources) {
            Evidence source=evidence.evidence(sourceId);if(source.storagePolicy()==StoragePolicy.NO_STORE)continue;
            String id="evidence_"+UUID.randomUUID();String payload=source.payloadRef()==null?null:records.payload(source.payloadRef(),owner.id());
            if(payload!=null && !CanonicalJson.sha256(json.readTree(payload)).equals(source.payloadHash()))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            var copy=new Evidence("1.0.0",id,String.format("E%02d",bound.size()+1),run.runId(),source.type(),source.provider(),source.sourceUrl(),source.instrumentId(),
                    source.asOf(),source.retrievedAt(),source.quality(),run.dataMode(),source.payloadHash(),source.storagePolicy(),payload==null?null:id,
                    source.modelInputAllowed() && payload!=null,source.issues(),source.missingSessions());
            saveEvidence(run.runId(),copy,payload);bound.add(copy);evidenceIds.put(sourceId,id);
        }
        if(scenario==null) {
            String id="evidence_"+UUID.randomUUID();String payload=json.writeValueAsString(Map.of("decision",decision,"portfolio",portfolio));
            var input=new Evidence("1.0.0",id,String.format("E%02d",bound.size()+1),run.runId(),"USER_INPUT","USER_INPUT",null,null,portfolio.asOf(),
                    MarketData.timestamp(clock.instant()),MarketQuality.Quality.VALID,run.dataMode(),CanonicalJson.sha256(json.readTree(payload)),StoragePolicy.PAYLOAD_ALLOWED,id,true,List.of(),List.of());
            saveEvidence(run.runId(),input,payload);bound.add(input);
            var before=new BigDecimal(portfolio.positions().stream().filter(position->position.instrumentId().equals(decision.action().instrumentId())).findFirst().orElseThrow().weight());
            BigDecimal after=decision.action().targetWeight()!=null?new BigDecimal(decision.action().targetWeight()):before.add(new BigDecimal(decision.action().deltaPercentagePoints()).movePointLeft(2));
            String inputHash=CanonicalJson.sha256(json.valueToTree(Map.of("portfolioHash",portfolio.contentHash(),"action",decision.action())));
            var params=new MetricData.Parameters(null,null,portfolio.valuationBasis(),false);
            sourceMetrics.add(factory.create("WEIGHT_CHANGE",MetricFactory.decimal(after.subtract(before).movePointRight(2)),"PERCENTAGE_POINT",null,inputHash,"weight-change-v1",params,null,0,MarketQuality.Quality.VALID,null,List.of(id)));
            sourceMetrics.add(factory.create("TARGET_WEIGHT_AFTER",MetricFactory.decimal(after),"RATIO",null,inputHash,"weight-after-v1",params,null,0,MarketQuality.Quality.VALID,null,List.of(id)));
            evidenceIds.put(id,id);
        }
        var metricIds=new HashMap<String,String>();var publishedMetrics=new ArrayList<MetricData.Metric>();
        for(var metric:sourceMetrics) {
            String id="metric_"+UUID.randomUUID();metricIds.put(metric.metricId(),id);
            List<String> refs=metric.evidenceRefs().stream().map(evidenceIds::get).toList();
            if(refs.stream().anyMatch(Objects::isNull))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            publishedMetrics.add(new MetricData.Metric(metric.schemaVersion(),id,metric.name(),metric.status(),metric.value(),metric.unit(),metric.currency(),metric.inputHash(),metric.formulaVersion(),metric.parameters(),metric.sampleWindow(),metric.sampleCount(),metric.quality(),metric.reason(),refs));
        }
        String snapshotId="research_"+UUID.randomUUID();var gaps=new ArrayList<>(tools.gaps());gaps.add(new Gap("MACRO_UNVERIFIED",null,"没有获准宏观因果证据，用户假设保持未验证"));
        gaps.sort(Comparator.comparing(Gap::code).thenComparing(Gap::ref,Comparator.nullsFirst(Comparator.naturalOrder())));
        var metadata=new ArrayList<Map<String,Object>>();
        for(var position:portfolio.positions())metadata.add(new LinkedHashMap<>(Map.of("instrumentId",position.instrumentId(),"inputIdentity",catalog.require(position.instrumentId(),run.dataMode(),"instrumentId"))));
        var manifest=json.createObjectNode();manifest.put("schemaVersion","1.0.0");
        var marketManifest=new ArrayList<JsonNode>();
        var marketContents=new ArrayList<MarketContent>();
        for(var input:calculation.marketInputs())if(input.payloadRef()!=null && input.inputHash()!=null) {
            var content=json.readValue(records.payload(input.payloadRef(),owner.id()),MarketContent.class);var instrument=content.instrument();
            if(!CanonicalJson.sha256(json.valueToTree(content)).equals(input.inputHash()))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            marketContents.add(content);
            metadata.stream().filter(value->value.get("instrumentId").equals(input.instrumentId())).findFirst().orElseThrow().put("marketInstrument",instrument);
            marketManifest.add(json.valueToTree(Map.of("evidenceRef",evidenceIds.get(input.evidenceRef()),"interval","1d","adjustment",instrument.adjustment().name(),"calendarId",instrument.calendarId(),
                    "calendarVersion",instrument.calendarVersion(),"exchangeTimezone",instrument.exchangeTimezone(),"window",Map.of("start",content.start(),"end",content.end(),"interval","1d"),"sampleCount",content.bars().size(),"inputHash",input.inputHash())));
        }
        manifest.set("instruments",json.valueToTree(metadata.stream().map(value->Map.of("instrumentId",value.get("instrumentId").toString(),"metadataHash",CanonicalJson.sha256(json.valueToTree(value)))).sorted(Comparator.comparing(value->value.get("instrumentId"))).toList()));
        manifest.set("marketInputs",json.valueToTree(marketManifest.stream().sorted(Comparator.comparing(value->value.get("evidenceRef").asString())).toList()));
        manifest.set("evidence",json.valueToTree(bound.stream().sorted(Comparator.comparing(Evidence::evidenceId)).map(value->{var entry=json.createObjectNode();entry.put("evidenceId",value.evidenceId());entry.put("payloadHash",value.payloadHash());entry.put("storagePolicy",value.storagePolicy().name());return entry;}).toList()));
        manifest.set("metrics",json.valueToTree(publishedMetrics.stream().sorted(Comparator.comparing(MetricData.Metric::metricId)).map(value->Map.of("metricId",value.metricId(),"inputHash",value.inputHash(),"formulaVersion",value.formulaVersion(),"contentHash",CanonicalJson.sha256(json.valueToTree(value)))).toList()));
        manifest.put("planHash",tools.planHash());manifest.put("engineVersion",ENGINE_VERSION);manifest.put("qualityPolicyVersion",MarketQuality.VERSION);
        manifest.set("promptVersions",json.valueToTree(Map.of("planner","planner-v1","bull","bull-v1","bear","bear-v1","judge","judge-v1")));
        manifest.put("modelConfigVersion",CanonicalJson.sha256(json.valueToTree(Map.of("model",model,"disableThinking",disableThinking,"maxTokens",2048))));
        manifest.put("replayable",calculation.replayable());manifest.set("gaps",json.valueToTree(gaps));
        var snapshot=json.createObjectNode();snapshot.put("schemaVersion","1.0.0");snapshot.put("runId",run.runId());snapshot.put("hashAlgorithm",CanonicalJson.ALGORITHM);
        snapshot.set("decisionRef",json.valueToTree(new ScenarioData.DecisionRef(run.decisionId(),run.decisionVersion())));snapshot.set("portfolioRef",json.valueToTree(calculation.portfolioRef()));snapshot.put("dataMode",run.dataMode().name());snapshot.set("manifest",manifest);
        String hash=CanonicalJson.sha256(snapshot);snapshot.put("snapshotId",snapshotId);snapshot.put("snapshotHash",hash);snapshot.put("frozenAt",MarketData.timestamp(clock.instant()));
        marketContents.sort(Comparator.comparing(value->value.instrument().instrumentId()));
        var inputContent=json.valueToTree(new InputContent("1.0.0",ENGINE_VERSION,portfolio.contentHash(),decision.action(),scenario==null?List.of():scenario.shocks().stream().map(value->(Object)value).toList(),marketContents,
                Map.of("dataMode",run.dataMode().name(),"valuationBasis",portfolio.valuationBasis().name(),"costsIncluded",false,"constraints",decision.constraints(),"annualizationFactor",252,"minimumSamples",quantPolicy.minimumSamples())));
        String combinedHash=CanonicalJson.sha256(inputContent);
        JsonNode bundle=json.valueToTree(Map.of("schemaVersion","1.0.0","bundleId","bundle_"+UUID.randomUUID(),"runId",run.runId(),"researchSnapshotId",snapshotId,"snapshotHash",hash,"inputHash",combinedHash,"engineVersion",ENGINE_VERSION,"metrics",publishedMetrics));
        JsonNode scenarioResult=null;
        if(scenario!=null) {
            var output=json.createObjectNode();output.put("schemaVersion","1.0.0");output.put("scenarioId",scenario.scenarioId());output.put("runId",run.runId());output.put("researchSnapshotId",snapshotId);output.put("snapshotHash",hash);output.put("inputHash",scenario.inputHash());
            output.set("beforeWeights",json.valueToTree(scenario.beforeWeights().stream().map(weight->Map.of("instrumentId",weight.instrumentId(),"quoteCurrency",portfolio.baseCurrency(),"weight",weight.weight())).toList()));
            output.set("afterWeights",json.valueToTree(scenario.afterWeights().stream().map(weight->Map.of("instrumentId",weight.instrumentId(),"quoteCurrency",portfolio.baseCurrency(),"weight",weight.weight())).toList()));
            output.set("metricRefs",json.valueToTree(scenario.metricRefs().stream().map(metricIds::get).toList()));
            output.set("ruleResults",json.valueToTree(scenario.ruleResults().stream().map(rule->new ScenarioData.Rule(rule.ruleId(),rule.status(),rule.threshold(),rule.unit(),rule.metricRefs().stream().map(metricIds::get).toList(),rule.reason())).toList()));scenarioResult=output;
        }
        var frozen=new ResearchData.Frozen(snapshot,json.valueToTree(decision),json.valueToTree(portfolio),plan,bundle,scenarioResult,bound,json.valueToTree(metadata),inputContent);
        mapper.insertFrozen(snapshotId,run.runId(),hash,json.writeValueAsString(frozen));
        if(mapper.bindFrozen(run.runId(),snapshotId,hash,"PARTIAL")!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return frozen;
    }
    private void saveEvidence(String run,Evidence value,String payload) {
        records.insertEvidence(value.evidenceId(),owner.id(),value.storagePolicy().name(),json.writeValueAsString(value),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        if(payload!=null && records.insertPayload(value.evidenceId(),payload)!=1)throw new BusinessException(ErrorCode.INVALID_STATE);
        mapper.linkEvidence(run,value.evidenceId());
    }
}
