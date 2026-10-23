package org.jack.backend.report;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.evidence.EvidenceData;
import org.jack.backend.research.ResearchFreezeService;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class ReportService {
    public record EvidenceBoard(List<EvidenceData.Evidence> items,JsonNode gaps) { }
    private final ReportMapper reports;
    private final WorkflowMapper workflow;
    private final WorkflowStore store;
    private final CurrentOwner owner;
    private final ReportStructureValidator structure;
    private final ReportNumbers numbers;
    private final ContractSchemas schemas;
    private final ResearchFreezeService snapshots;
    private final ObjectMapper json;
    private final Clock clock;
    public ReportService(ReportMapper reports,WorkflowMapper workflow,WorkflowStore store,CurrentOwner owner,ReportStructureValidator structure,
            ReportNumbers numbers,ContractSchemas schemas,ResearchFreezeService snapshots,ObjectMapper json,Clock clock) {
        this.reports=reports;this.workflow=workflow;this.store=store;this.owner=owner;this.structure=structure;this.numbers=numbers;this.schemas=schemas;this.snapshots=snapshots;this.json=json;this.clock=clock;
    }
    @Transactional
    public void publish(String runId,JsonNode candidate) {
        var run=workflow.lockRun(runId,owner.id());
        if(run==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if(run.status()!=RunStatus.RUNNING)throw new BusinessException(ErrorCode.INVALID_STATE);
        var checkpoint=store.step(runId,StepKey.VALIDATE);
        if(checkpoint.status()!=StepStatus.SUCCEEDED || checkpoint.outputRef()==null)throw new BusinessException(ErrorCode.INVALID_STATE);
        var validated=json.readTree(workflow.output(checkpoint.outputRef()));
        if(!validated.path("numericValidationComplete").asBoolean() || !candidate.equals(validated.get("report")))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        structure.validate(runId,candidate);var figures=numbers.validate(candidate);
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
        String time=now.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'"));
        ObjectNode report=(ObjectNode)candidate.deepCopy();report.put("validatedAt",time);report.put("publishedAt",time);schemas.require("Report",report);
        String id=report.get("reportId").asString();reports.insert(id,runId,json.writeValueAsString(report),json.writeValueAsString(figures),now);
        reports.verdict(id,json.writeValueAsString(report.get("verdict")));
        for(JsonNode ref:report.get("evidenceRefs"))reports.link(id,ref.asString());
        if(reports.finish(runId,owner.id(),run.revision(),id,report.get("status").asString(),now)!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        var completed=store.get(runId);
        workflow.insertEvent(new Event(runId+":"+completed.eventSequence(),runId,completed.eventSequence(),EventType.RUN_TERMINATED,Stage.VALIDATE,completed.status(),now,"REPORT_PUBLISHED"));
    }
    @Transactional(readOnly=true)
    public JsonNode get(String runId) {
        var run=store.get(runId);String body=reports.get(runId,owner.id());
        if(body==null)throw new BusinessException(ErrorCode.REPORT_UNAVAILABLE);
        var report=json.readTree(body);schemas.require("Report",report);
        if(!Objects.equals(run.reportId(),report.get("reportId").asString()))throw new BusinessException(ErrorCode.REPORT_UNAVAILABLE);
        return report;
    }
    @Transactional(readOnly=true)
    public JsonNode figures(String runId) {get(runId);return json.readTree(reports.figures(runId,owner.id()));}
    @Transactional(readOnly=true)
    public EvidenceBoard evidence(String runId) {
        var run=store.get(runId);
        if(run.researchSnapshotId()==null)return new EvidenceBoard(List.of(),json.createArrayNode());
        var frozen=snapshots.get(runId);
        return new EvidenceBoard(frozen.evidence().stream().filter(value->value.storagePolicy()!=EvidenceData.StoragePolicy.NO_STORE).toList(),frozen.snapshot().get("manifest").get("gaps"));
    }
}
