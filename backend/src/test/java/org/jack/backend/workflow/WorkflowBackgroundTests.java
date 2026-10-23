package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

@TestPropertySource(properties={"investlens.workflow.scheduler.enabled=true","investlens.workflow.scheduler.delay-ms=50"})
class WorkflowBackgroundTests extends WorkflowIntegrationSupport {
    @MockitoBean WorkflowHandler handler;
    @Test void acceptedHttpReturnsWhileBackgroundContinuesThroughRealCheckpoints() throws Exception {
        var f=fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=new PreparationHandler(decisions,portfolios,null,false);
        when(handler.execute(any())).thenAnswer(call->{var c=(WorkflowClaimService.Claim)call.getArgument(0);if(c.step().stepKey()==StepKey.PLAN){entered.countDown();release.await(10,TimeUnit.SECONDS);}return real.execute(c);});
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/decisions/"+f.decisionId()+"/runs"))
                    .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(f.request()))).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(202);
            String id=json.readTree(response.body()).at("/data/runId").asString();runIds.add(id);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(store.get(id).status()).isEqualTo(RunStatus.RUNNING);assertThat(store.step(id,StepKey.PLAN).outputRef()).isNull();release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!store.get(id).status().terminal() && System.nanoTime()<until)Thread.sleep(20);
            assertThat(store.get(id).status()).isEqualTo(RunStatus.FAILED);assertThat(store.get(id).errorCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");assertThat(store.get(id).reportId()).isNull();
            for(var key:new StepKey[]{StepKey.PLAN,StepKey.RESEARCH,StepKey.QUANT}){var s=store.step(id,key);assertThat(s.status()).isEqualTo(StepStatus.SUCCEEDED);assertThat(workflowMapper.output(s.outputRef())).isNotBlank();}
            var quant=json.readTree(workflowMapper.output(store.step(id,StepKey.QUANT).outputRef()));assertThat(quant.get("hhi").asString()).isEqualTo("0.54");
            assertThat(store.step(id,StepKey.BULL).status()).isEqualTo(StepStatus.FAILED);assertThat(store.events(id)).hasSize(10);
            assertThat(send("GET","/api/runs/"+id,null).statusCode()).isEqualTo(200);
        }finally{release.countDown();}
    }
}
