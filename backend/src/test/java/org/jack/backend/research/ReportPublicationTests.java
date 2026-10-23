package org.jack.backend.research;

import org.jack.backend.report.*;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

class ReportPublicationTests extends ReportStructureTests {
    @Autowired ReportService reports;
    @Autowired PlatformTransactionManager transactions;
    private HttpResponse<String> get(String path) throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    @Test void rollbackRestoresCheckpointReportVerdictReferencesAndTerminalEventThenRetryIsIdempotent() throws Exception {
        var ready=ready();var claim=claims.claimNext();var result=handler.execute(claim);int before=store.events(ready.runId()).size();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{checkpoints.save(claim,result);throw new IllegalStateException("simulated commit failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(store.step(ready.runId(),StepKey.VALIDATE).status()).isEqualTo(StepStatus.RUNNING);
        assertThat(store.get(ready.runId()).reportId()).isNull();assertThat(store.events(ready.runId())).hasSize(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE run_id=?",Integer.class,ready.runId())).isZero();
        checkpoints.save(claim,result);String body=json.writeValueAsString(reports.get(ready.runId()));checkpoints.save(claim,result);
        assertThat(json.writeValueAsString(reports.get(ready.runId()))).isEqualTo(body);assertThat(store.events(ready.runId())).hasSize(before+2);
        var report=reports.get(ready.runId());assertThat(report.get("publishedAt").asString()).isEqualTo(report.get("validatedAt").asString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verdicts WHERE report_id=?",Integer.class,report.get("reportId").asString())).isEqualTo(1);
        assertThat(reports.figures(ready.runId()).size()).isGreaterThanOrEqualTo(7);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g32-report.json"),body);
    }
    @Test void httpHistoryReadsOriginalReportsAndRejectsBadCursorsWithoutCreatingRuns() throws Exception {
        var ready=ready();assertThat(get("/runs/"+ready.runId()+"/report").statusCode()).isEqualTo(202);
        var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));var run=store.get(ready.runId());
        assertThat(get("/runs/"+ready.runId()+"/report").statusCode()).isEqualTo(200);
        assertThat(get("/runs/"+ready.runId()+"/evidence").body()).doesNotContain("payload_json");
        var page=get("/decisions?limit=1");assertThat(page.statusCode()).isEqualTo(200);
        assertThat(get("/decisions?cursor=not-a-cursor").statusCode()).isEqualTo(400);
        assertThat(get("/decisions?limit=0").statusCode()).isEqualTo(400);
        var history=get("/decisions/"+run.decisionId()+"/runs");assertThat(history.statusCode()).isEqualTo(200);assertThat(history.body()).contains(run.runId(),run.reportId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_runs WHERE decision_id=?",Integer.class,run.decisionId())).isEqualTo(1);
        assertThat(get("/runs/unknown/report").statusCode()).isEqualTo(404);
    }
}
