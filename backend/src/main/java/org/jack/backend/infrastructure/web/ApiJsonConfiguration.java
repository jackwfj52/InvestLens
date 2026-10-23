package org.jack.backend.infrastructure.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

@Configuration(proxyBeanMethods = false)
public class ApiJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictApiJson() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
