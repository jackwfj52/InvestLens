package org.jack.backend.infrastructure.web;

import org.jack.backend.common.api.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** Defense in depth for credential fields, without touching SSE or non-envelope bodies. */
@RestControllerAdvice
public class SensitiveFieldResponseAdvice implements ResponseBodyAdvice<Object> {
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "apikey", "apisecret", "accesskey", "accesskeyid", "accesskeysecret", "secretaccesskey",
            "secretkey", "password", "passwd", "passphrase", "authorization",
            "proxyauthorization", "token", "accesstoken", "refreshtoken", "clientsecret",
            "credentials", "secret", "privatekey", "executiontoken", "bearertoken", "sessiontoken",
            "cookie", "setcookie");
    private final ObjectMapper mapper;

    public SensitiveFieldResponseAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ApiResponse<?>)) {
            return body;
        }
        JsonNode tree = mapper.valueToTree(body);
        removeCredentials(tree);
        return tree;
    }

    private void removeCredentials(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (var entry : new ArrayList<>(object.properties())) {
                String normalized = entry.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (SENSITIVE_FIELDS.contains(normalized)) {
                    object.remove(entry.getKey());
                } else {
                    removeCredentials(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::removeCredentials);
        }
    }
}
