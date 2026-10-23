package org.jack.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Requires the real dev MySQL and Redis; no embedded database or mock fallback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class BackendApplicationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Flyway flyway;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @Test
    void contextLoads() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).doesNotStartWith("root@");
        assertThat(jdbc.queryForObject("SELECT @@character_set_database", String.class)).isEqualTo("utf8mb4");
        assertThat(jdbc.queryForObject("SELECT @@collation_database", String.class)).isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        flyway.validate();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class))
                .isEqualTo(1);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void redisRoundTripUsesExpiringDisposableKey() {
        String key = "investlens:integration:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "g04", Duration.ofSeconds(30));
            assertThat(redis.opsForValue().get(key)).isEqualTo("g04");
            assertThat(redis.getExpire(key)).isBetween(1L, 30L);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void realHttpHealthIsMinimalAndSensitiveEndpointsAreClosed() throws Exception {
        for (String path : new String[]{"health", "health/liveness", "health/readiness"}) {
            HttpResponse<String> response = get("/actuator/" + path);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.get("status").asString()).isEqualTo("UP");
            assertThat(body.has("components")).isFalse();
            assertThat(body.has("details")).isFalse();
            assertThat(body.has("schemaVersion")).isFalse();
        }
        for (String endpoint : new String[]{"env", "configprops", "flyway", "shutdown"}) {
            assertThat(get("/actuator/" + endpoint).statusCode()).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

}
