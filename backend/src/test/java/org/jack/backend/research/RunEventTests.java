package org.jack.backend.research;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

class RunEventTests extends ResearchPipelineSupport {
    @Autowired RunEventService events;
    @Autowired ContractSchemas schemas;
    @Test void jsonAndSseReplayOnlyPersistedOrderedFactsAndTerminalStreamEnds() throws Exception {
        var run=scenarioRun();throughQuant();store.cancel(run.runId());
        var batch=events.read(run.runId(),0);assertThat(batch.finished()).isTrue();long sequence=0;
        for(var event:batch.page().items()){assertThat(event.sequence()).isEqualTo(++sequence);schemas.require("RunEvent",json.valueToTree(event));}
        assertThat(events.read(run.runId(),sequence).page().items()).isEmpty();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+run.runId()+"/events?afterSequence=1")).header("Accept","text/event-stream").GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id:"+run.runId()+":2","event:progress","RUN_TERMINATED").doesNotContain("id:"+run.runId()+":1\n");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        var normal=send("GET","/api/runs/"+run.runId()+"/events?afterSequence=1",null);assertThat(normal.statusCode()).isEqualTo(200);assertThat(json.readTree(normal.body()).get("data").get("nextSequence").asLong()).isEqualTo(sequence);
    }
    @Test void badAndForeignCursorsFailAndDisconnectDoesNotCancel() throws Exception {
        var run=scenarioRun();assertThatThrownBy(()->events.read(run.runId(),-1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->events.read(run.runId(),99999)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->events.read("foreign",0)).isInstanceOf(BusinessException.class);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+run.runId()+"/events")).header("Accept","text/event-stream").GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofInputStream());response.body().close();
        assertThat(store.get(run.runId()).status()).isEqualTo(WorkflowData.RunStatus.QUEUED);throughQuant();
        assertThat(store.get(run.runId()).researchSnapshotId()).isNotNull();store.cancel(run.runId());
    }
    @Test void lastEventIdOverridesQueryAndRejectsAnotherRunMalformedAndFutureCursors() throws Exception {
        var run=scenarioRun();
        for(int index=0;index<3;index++) {
            var claim=claims.claimNext();assertThat(claim).describedAs("step %s; current run %s",index,store.get(run.runId())).isNotNull();
            assertThat(claim.run().runId()).isEqualTo(run.runId());checkpoints.save(claim,handler.execute(claim));
        }
        store.cancel(run.runId());
        for(String cursor:new String[]{"other:1",run.runId()+":0",run.runId()+":-1",run.runId()+":01",run.runId()+":999999",run.runId()+":1:2"}) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+run.runId()+"/events")).header("Last-Event-ID",cursor).GET().build();
            assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        }
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+run.runId()+"/events?afterSequence=invalid")).header("Last-Event-ID",run.runId()+":2").header("Accept","text/event-stream").GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id:"+run.runId()+":3").doesNotContain("id:"+run.runId()+":2\n");
    }
}
