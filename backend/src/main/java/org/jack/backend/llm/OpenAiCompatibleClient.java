package org.jack.backend.llm;

import org.jack.backend.common.exception.*;
import org.jack.backend.workflow.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class OpenAiCompatibleClient implements LlmClient {
    private final ObjectMapper json;
    private final String baseUrl,model,key;
    private final boolean enabled;
    private final boolean disableThinking;
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    public OpenAiCompatibleClient(ObjectMapper json,Clock clock,
            @Value("${LLM_BASE_URL:}") String baseUrl,
            @Value("${LLM_MODEL:}") String model,
            @Value("${LLM_API_KEY:${DEEPSEEK_API_KEY:}}") String key,
            @Value("${INVESTLENS_LLM_ENABLED:false}") boolean enabled,
            @Value("${LLM_DISABLE_THINKING:false}") boolean disableThinking) {
        this.json=json;this.clock=clock;this.baseUrl=baseUrl;this.model=model;this.key=key;this.enabled=enabled;
        this.disableThinking=disableThinking;
    }
    @Override public Reply complete(Request request,long timeoutMillis) {
        if(!enabled || key.isBlank() || model.isBlank() || baseUrl.isBlank())throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if(timeoutMillis<=0)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        URI uri;
        try {
            uri=URI.create(baseUrl.replaceAll("/+$","")+"/chat/completions");
            if(uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost()))))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);}
        if(request==null || request.system()==null || request.input()==null || request.maxTokens()<1 || request.maxTokens()>4096)throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        var payload=new LinkedHashMap<String,Object>(Map.of("model",model,"stream",false,"max_tokens",request.maxTokens(),
                "response_format",Map.of("type","json_object"),
                "messages",List.of(Map.of("role","system","content",request.system()),Map.of("role","user","content",request.input()))));
        if(disableThinking)payload.put("thinking",Map.of("type","disabled"));
        String body=json.writeValueAsString(payload);
        var wire=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(Math.min(30000,timeoutMillis)))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending=http.sendAsync(wire,info->new LimitedBody());
        HttpResponse<byte[]> response;
        try{response=pending.get(Math.min(30000,timeoutMillis),TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);}
        catch(TimeoutException timeout){pending.cancel(true);throw new UpstreamFailure(ErrorCode.UPSTREAM_TIMEOUT,true,0);}
        catch(ExecutionException failure){
            pending.cancel(true);Throwable cause=failure.getCause();
            while(cause!=null) {
                if(cause instanceof HttpTimeoutException)throw new UpstreamFailure(ErrorCode.UPSTREAM_TIMEOUT,true,0);
                if(cause instanceof IllegalArgumentException)throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
                cause=cause.getCause();
            }
            throw new UpstreamFailure(ErrorCode.UPSTREAM_ERROR,true,0);
        }
        int status=response.statusCode();
        if(status==401 || status==403)throw new UpstreamFailure(ErrorCode.PROVIDER_NOT_CONFIGURED,false,0);
        if(status==429)throw new UpstreamFailure(ErrorCode.RATE_LIMITED,true,UpstreamFailure.retryAfter(response.headers().firstValue("Retry-After").orElse(null),clock));
        if(status<200 || status>=300)throw new UpstreamFailure(ErrorCode.UPSTREAM_ERROR,status>=500,0);
        try {
            JsonNode root=json.readTree(response.body());
            JsonNode choice=root.path("choices").path(0), content=choice.path("message").path("content");
            if(!"stop".equals(choice.path("finish_reason").asString()) || !content.isString() || content.asString().isBlank()
                    || content.asString().length()>65536)throw new IllegalArgumentException();
            JsonNode usage=root.path("usage");
            long prompt=token(usage,"prompt_tokens"),completion=token(usage,"completion_tokens"),total=token(usage,"total_tokens");
            String actual=root.path("model").asString();
            if(actual==null || !actual.matches("[A-Za-z0-9_.:/-]{1,128}") || total!=prompt+completion || actual.contains(key) || content.asString().contains(key))throw new IllegalArgumentException();
            return new Reply(content.asString(),actual,"stop",new Usage(prompt,completion,total));
        }catch(RuntimeException invalid){throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
    }
    private static long token(JsonNode usage,String name) {
        JsonNode value=usage.path(name);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0 || value.asLong()>10000000)throw new IllegalArgumentException();
        return value.asLong();
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers)size+=buffer.remaining();
            if(size>1048576){subscription.cancel();delegate.onError(new IllegalArgumentException());}else delegate.onNext(buffers);
        }
        public void onError(Throwable failure){delegate.onError(failure);}
        public void onComplete(){delegate.onComplete();}
    }
}
