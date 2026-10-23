package org.jack.backend.llm;

public interface LlmClient {
    record Request(String system,String input,int maxTokens) { }
    record Usage(long promptTokens,long completionTokens,long totalTokens) { }
    record Reply(String content,String model,String finishReason,Usage usage) { }
    Reply complete(Request request,long timeoutMillis);
}
