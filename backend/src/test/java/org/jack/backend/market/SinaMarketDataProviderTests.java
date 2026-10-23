package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.tool.MarketHistoryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.market.MarketData.*;

class SinaMarketDataProviderTests {
    private final SinaProcessTransport transport = mock(SinaProcessTransport.class);
    private final MarketInstrumentCatalog catalog = new MarketInstrumentCatalog();
    private final SinaMarketDataProvider provider = new SinaMarketDataProvider(transport, catalog, JsonMapper.builder().build());
    private final Query query = new Query("ETF:SINA:NYSE_ARCA:GLD:USD", LocalDate.parse("2026-06-24"), LocalDate.parse("2026-06-25"));
    private final String valid = """
        {"schemaVersion":"1.0.0","status":"OK","failureCode":null,"retrievedAt":"2026-06-26T00:00:00.000Z",
         "bars":[{"sessionDate":"2026-06-24","open":"100","high":"102","low":"99","close":"101"}]}
        """;
    @Test void mapsOnlyRegisteredMetadataAndNeutralStrings() {
        when(transport.exchange(anyString())).thenReturn(valid);
        History history = provider.history(query);
        assertThat(history.instrument().proxyFor()).isEqualTo("GOLD");
        assertThat(history.instrument().quoteCurrency()).isEqualTo("USD");
        assertThat(history.instrument().priceUnit()).isEqualTo("CURRENCY_PER_SHARE");
        assertThat(history.instrument().adjustment()).isEqualTo(Adjustment.UNKNOWN);
        assertThat(history.source()).isEqualTo("https://finance.sina.com.cn/staticdata/us/GLD");
        assertThat(history.bars().get(0).close()).isEqualTo("101");
        assertThat(history.asOf()).isNull(); // Only the calendar quality pass can establish a final asOf.
        assertThat(history.retrievedAt()).isEqualTo("2026-06-26T00:00:00.000Z");
        verify(transport).exchange(contains("\"symbol\":\"GLD\""));
    }
    @ParameterizedTest @CsvSource({"TIMEOUT,UPSTREAM_TIMEOUT", "RATE_LIMITED,RATE_LIMITED", "ACCESS_DENIED,UPSTREAM_ERROR",
            "AUTHENTICATION_FAILED,UPSTREAM_ERROR", "NO_HISTORY,DATA_UNUSABLE", "SINA_DECODER_NOT_INSTALLED,PROVIDER_NOT_CONFIGURED"})
    void errorsMapToSafeBusinessCodes(String wireCode, String code) {
        when(transport.exchange(anyString())).thenReturn("{\"schemaVersion\":\"1.0.0\",\"status\":\"ERROR\",\"failureCode\":\"" + wireCode + "\",\"bars\":[],\"retrievedAt\":null}");
        assertThatThrownBy(() -> provider.history(query)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode().name()).isEqualTo(code);
            assertThat(error.getCause()).isNull();
        });
    }
    @Test void emptyMalformedOutOfWindowAndFutureResponsesFail() {
        for (String body : new String[]{"", "<html>secret</html>", "null", valid.replace("2026-06-24\"", "2026-06-23\""), valid.replace("2026-06-26T", "2027-06-26T"), valid.replace("\"OK\"", "\"ALIEN\"")}) {
            when(transport.exchange(anyString())).thenReturn(body);
            assertThatThrownBy(() -> provider.history(query)).isInstanceOf(BusinessException.class);
        }
        when(transport.exchange(anyString())).thenReturn(valid.replaceFirst("(?s)\\[.*]", "[]"));
        assertThatThrownBy(() -> provider.history(query)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DATA_UNUSABLE));
    }
    @Test void unsupportedAndOversizedRequestsMakeNoProcessCall() {
        assertThatThrownBy(() -> provider.history(new Query("GLD", query.start(), query.end()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> provider.history(new Query(query.instrumentId(), LocalDate.parse("2026-01-01"), query.end()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transport);
    }
    @Test void pendingQualificationCannotLeakPricesToPublicTool() {
        assertThat(catalog.candidates()).hasSize(3).allSatisfy(candidate -> assertThat(candidate.qualification()).isEqualTo("PENDING"));
        assertThatThrownBy(() -> new MarketHistoryTool(provider, catalog).execute(query)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LICENSE_BLOCKED));
        verifyNoInteractions(transport);
    }
    @Test void windowUsesNewYorkDateAndIncludesOnly120CalendarDays() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T02:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> MarketWindow.validate(new Query(query.instrumentId(), LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-23")), clock))
                .isInstanceOf(BusinessException.class);
        MarketWindow.validate(query, clock);
    }
    @Test void disabledProcessIsExplicitlyUnavailable() {
        assertThatThrownBy(() -> new SinaProcessTransport("missing", "node", "missing.py", false).exchange("{}"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED));
    }
}
