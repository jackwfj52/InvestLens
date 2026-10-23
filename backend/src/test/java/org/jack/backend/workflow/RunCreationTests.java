package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Import(WorkflowIntegrationTests.FaultConfiguration.class)
class RunCreationTests extends WorkflowIntegrationSupport {
    @Autowired WorkflowIntegrationTests.EventFault fault;
    @Test void eventFailureRollsBackRunStepAndKeyAndUnconfirmedInputNeverQueues() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString();fault.fail.set(true);
        assertThatThrownBy(()->creation.create(f.decisionId(),key,f.request())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_runs WHERE decision_id=?",Integer.class,f.decisionId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key=?",Integer.class,key)).isZero();
        var draft=(tools.jackson.databind.node.ObjectNode)json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        ((tools.jackson.databind.node.ObjectNode)draft.get("proposedDecision")).put("portfolioSnapshotId",f.request().portfolioSnapshotId());
        var d=decisions.create(json.treeToValue(draft,org.jack.backend.decision.DecisionDtos.Draft.class));decisionIds.add(d.decisionId());
        assertThatThrownBy(()->creation.create(d.decisionId(),key,f.request())).isInstanceOf(BusinessException.class);
        var r=queued(f);var p=portfolios.getSnapshot(r.portfolioSnapshotId());
        portfolios.revise(p.portfolioId(),new org.jack.backend.portfolio.PortfolioDtos.Revision("1.0.0",1,p.positions(),"2026-10-23T02:00:00.000Z"));
        assertThat(store.get(r.runId()).portfolioSnapshotId()).isEqualTo(p.snapshotId());
    }
    @Test void parallelSameKeyCreatesOneDurableRunAndOneInitialStepEvent() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString(); var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try {
            List<Future<Run>> results=new ArrayList<>();
            for(int i=0;i<4;i++) results.add(pool.submit(()->{gate.await();return creation.create(f.decisionId(),key,f.request());}));
            gate.countDown();var ids=new HashSet<String>();for(var result:results)ids.add(result.get(10,TimeUnit.SECONDS).runId());
            runIds.addAll(ids);assertThat(ids).hasSize(1);var r=store.get(runIds.get(0));assertThat(r.status()).isEqualTo(RunStatus.QUEUED);
            assertThat(store.step(r.runId(),StepKey.PLAN).attempt()).isZero();assertThat(store.events(r.runId())).extracting(Event::type).containsExactly(EventType.RUN_QUEUED);
            assertThatThrownBy(()->creation.create(f.decisionId(),key,new RunDtos.Create("1.0.0",1,f.request().portfolioSnapshotId(),2,f.request().dataMode())))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
            store.transition(r.runId(),r.revision(),RunStatus.FAILED,null,"TEST");
            assertThat(creation.create(f.decisionId(),key,f.request()).status()).isEqualTo(RunStatus.FAILED);
            assertThat(queued(f).runId()).isNotEqualTo(r.runId());
        } finally {pool.shutdownNow();}
    }
    @Test void mismatchRollsBackReservationAndInvalidKeyIsRejected() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString();
        assertThatThrownBy(()->creation.create(f.decisionId(),key,new RunDtos.Create("1.0.0",1,f.request().portfolioSnapshotId(),2,f.request().dataMode()))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThatThrownBy(()->creation.create(f.decisionId(),"非法 key",f.request())).isInstanceOf(BusinessException.class);
        var r=creation.create(f.decisionId(),key,f.request());runIds.add(r.runId());assertThat(r.portfolioVersion()).isEqualTo(1);
    }
    @Test void httpUses202LocationUtcTimeAndOriginalTerminalMapping() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/decisions/"+f.decisionId()+"/runs"))
                .header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(f.request()))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);var data=json.readTree(response.body()).get("data");String id=data.get("runId").asString();runIds.add(id);
        assertThat(response.headers().firstValue("Location")).contains("/api/runs/"+id);
        assertThat(data.get("createdAt").asString()).matches(".*\\.\\d{3}Z");assertThat(data.get("budget").get("remainingDataAttempts").asInt()).isEqualTo(8);
        assertThat(send("GET","/api/runs/"+id,null).statusCode()).isEqualTo(200);
        assertThat(send("GET","/api/runs/unknown",null).statusCode()).isEqualTo(404);
        var r=store.get(id);store.transition(id,r.revision(),RunStatus.FAILED,null,"TEST");
        var repeated=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertThat(repeated.statusCode()).isEqualTo(202);
        assertThat(json.readTree(repeated.body()).at("/data/status").asString()).isEqualTo("FAILED");
        assertThat(send("POST","/api/decisions/"+f.decisionId()+"/runs",f.request()).statusCode()).isEqualTo(400);
    }
}
