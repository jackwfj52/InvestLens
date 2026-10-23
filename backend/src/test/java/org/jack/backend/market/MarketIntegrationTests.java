package org.jack.backend.market;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class MarketIntegrationTests {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void metadataIsQueryableWithoutPretendingCandidatesAreQualified() throws Exception {
        var response = get("/api/market/instruments");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("data").size()).isEqualTo(3);
        assertThat(body.get("data").get(1).get("instrument").get("proxyFor").asString()).isEqualTo("GOLD");
        assertThat(body.get("data").get(0).get("qualification").asString()).isEqualTo("PENDING");
        assertThat(body.get("traceId").asString()).isNotBlank();
    }
    @Test void livePriceRouteStopsBeforeUnlicensedSourceCall() throws Exception {
        var response = get("/api/market/history?instrumentId=ETF:SINA:NYSE_ARCA:GLD:USD&start=2026-06-25&end=2026-10-22");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("LICENSE_BLOCKED").doesNotContain("finance.sina.com", "python.exe");
    }
    @Test void badWindowAndUnknownAssetHaveSafeFieldErrors() throws Exception {
        assertThat(get("/api/market/history?instrumentId=GLD&start=2026-06-25&end=2026-10-22").statusCode()).isEqualTo(400);
        assertThat(get("/api/market/history?instrumentId=GLD&start=2026-01-01&end=2026-10-22").body()).contains("window");
        assertThat(get("/api/market/history?instrumentId=GLD&start=invalid&end=2026-10-22").statusCode()).isEqualTo(400);
    }
    @Test void qualityAndVersionedCalendarAreQueryableWithoutExternalRequests() throws Exception {
        var quality = get("/api/market/quality?instrumentId=ETF:SINA:NYSE_ARCA:GLD:USD&start=2026-06-25&end=2026-10-22");
        assertThat(quality.statusCode()).isEqualTo(200);
        assertThat(quality.body()).contains("market-quality-v1", "LICENSE_BLOCKED", "MAINLAND_ACCESS_UNVERIFIED", "ADJUSTMENT_UNKNOWN", "\"calculationBars\":[]");
        var calendar = get("/api/market/quality-policy");
        assertThat(calendar.statusCode()).isEqualTo(200);
        assertThat(calendar.body()).contains("us-core-2026-v1", "2026-07-03", "2026-11-27", "finalizationDelayMinutes");
    }
}
