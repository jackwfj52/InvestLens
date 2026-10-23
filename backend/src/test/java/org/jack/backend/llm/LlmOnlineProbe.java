package org.jack.backend.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INVESTLENS_RUN_LLM_PROBE",matches="true")
class LlmOnlineProbe {
    @Test void fixedNonFinancialConnectivityMessage() {
        var client=new OpenAiCompatibleClient(JsonMapper.builder().build(),Clock.systemUTC(),
                System.getenv("LLM_BASE_URL"),System.getenv("LLM_MODEL"),System.getenv("LLM_API_KEY")==null?System.getenv("DEEPSEEK_API_KEY"):System.getenv("LLM_API_KEY"),true,true);
        var reply=client.complete(new LlmClient.Request("Return exactly one JSON object, no extra text.","Return JSON: {\"ok\":true,\"value\":2}. This is a connectivity test.",64),30000);
        var result=JsonMapper.builder().build().readTree(reply.content());
        assertThat(result.path("ok").asBoolean()).isTrue();assertThat(result.path("value").asInt()).isEqualTo(2);
        System.out.println("LLM_PROBE_OK finish="+reply.finishReason()+" totalTokens="+reply.usage().totalTokens());
    }
}
