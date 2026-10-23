package org.jack.backend.llm;

import com.sun.net.httpserver.*;
import org.jack.backend.common.exception.*;
import org.jack.backend.workflow.UpstreamFailure;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class LlmClientTests {
    HttpServer server;
    volatile int status=200;
    volatile long delay;
    volatile String response="""
        {"model":"stub-v1","choices":[{"message":{"content":"{\"ok\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}
        """.replace("{\"ok\":true}","{\\\"ok\\\":true}");
    volatile String received;
    ExecutorService executor;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/chat/completions",exchange->{
            received=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer stub-secret");
            try{Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            exchange.getResponseHeaders().add("Retry-After","2");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            try{exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    OpenAiCompatibleClient client(boolean enabled){return new OpenAiCompatibleClient(JsonMapper.builder().build(),Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort(),"stub-v1","stub-secret",enabled,false);}
    LlmClient.Reply call(long timeout){return client(true).complete(new LlmClient.Request("Return JSON","Synthetic connectivity check",64),timeout);}
    @Test void structuredRequestUsageAndFinishReason() {
        var reply=call(2000);assertThat(reply.content()).isEqualTo("{\"ok\":true}");assertThat(reply.usage().totalTokens()).isEqualTo(7);
        assertThat(received).contains("json_object").contains("\"stream\":false").doesNotContain("stub-secret");
    }
    @Test void classifiesAuthAndRateLimitWithoutRawError() {
        response="upstream body stub-secret";
        for(int code:new int[]{401,403,429,500,400}) {
            status=code;assertThatThrownBy(()->call(2000)).isInstanceOfSatisfying(UpstreamFailure.class,error->{
                assertThat(error.getMessage()).doesNotContain("stub-secret");assertThat(error.retryable()).isEqualTo(code==429 || code==500);
                if(code==429)assertThat(error.retryAfterMillis()).isEqualTo(2000);
            });
        }
    }
    @Test void dynamicTimeoutAndDisabledDefault() {
        delay=500;assertThatThrownBy(()->call(50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->client(false).complete(new LlmClient.Request("JSON","x",64),2000)).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED));
    }
    @Test void rejectsTruncationEmptyMalformedAndInvalidUsage() {
        String normal=response;
        for(String invalid:new String[]{"<html>stub-secret</html>",normal.replace("stop","length"),normal.replace("7}","-1}"),normal.replace("{\\\"ok\\\":true}","")}) {
            response=invalid;assertThatThrownBy(()->call(2000)).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MODEL_OUTPUT_INVALID));
        }
    }
}
