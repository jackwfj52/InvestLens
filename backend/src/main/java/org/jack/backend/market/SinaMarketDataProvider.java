package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.jack.backend.market.MarketData.*;

@Component
public class SinaMarketDataProvider implements MarketDataProvider {
    public record Wire(String schemaVersion, String status, List<Bar> bars, String failureCode, String retrievedAt) { }
    private final SinaProcessTransport transport;
    private final MarketInstrumentCatalog catalog;
    private final ObjectMapper json;
    public SinaMarketDataProvider(SinaProcessTransport transport, MarketInstrumentCatalog catalog, ObjectMapper json) {
        this.transport = transport; this.catalog = catalog; this.json = json;
    }
    @Override public History history(Query query) {
        MarketWindow.validate(query, Clock.systemUTC());
        Candidate candidate = catalog.requireCandidate(query.instrumentId());
        String request = json.writeValueAsString(Map.of("schemaVersion", "1.0.0", "symbol", candidate.instrument().symbol(),
                "start", query.start().toString(), "end", query.end().toString()));
        Wire wire;
        try { wire = json.readValue(transport.exchange(request), Wire.class); }
        catch (BusinessException error) { throw error; }
        catch (RuntimeException error) { throw new BusinessException(ErrorCode.UPSTREAM_ERROR); }
        if (wire == null || !"1.0.0".equals(wire.schemaVersion())) throw new BusinessException(ErrorCode.UPSTREAM_ERROR);
        if ("ERROR".equals(wire.status())) {
            throw new BusinessException(switch (wire.failureCode() == null ? "" : wire.failureCode()) {
                case "TIMEOUT" -> ErrorCode.UPSTREAM_TIMEOUT;
                case "RATE_LIMITED" -> ErrorCode.RATE_LIMITED;
                case "NO_HISTORY" -> ErrorCode.DATA_UNUSABLE;
                case "SINA_DECODER_NOT_INSTALLED", "SINA_DECODER_UNAVAILABLE", "DECODER_VERSION_MISMATCH", "DECODER_INTEGRITY_MISMATCH" -> ErrorCode.PROVIDER_NOT_CONFIGURED;
                default -> ErrorCode.UPSTREAM_ERROR;
            });
        }
        if (!"OK".equals(wire.status()) || wire.failureCode() != null || wire.bars() == null || wire.bars().size() > 500) {
            throw new BusinessException(ErrorCode.UPSTREAM_ERROR);
        }
        if (wire.bars().isEmpty()) throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        try {
            Instant retrieved = Instant.parse(wire.retrievedAt());
            if (!timestamp(retrieved).equals(wire.retrievedAt()) || retrieved.isAfter(Instant.now().plusSeconds(60))) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new BusinessException(ErrorCode.UPSTREAM_ERROR); }
        for (Bar bar : wire.bars()) {
            if (bar == null || bar.sessionDate() == null || bar.sessionDate().isBefore(query.start()) || bar.sessionDate().isAfter(query.end())
                    || bar.open() == null || bar.high() == null || bar.low() == null || bar.close() == null) throw new BusinessException(ErrorCode.UPSTREAM_ERROR);
        }
        // Before quality/calendar normalization there is no verified final session timestamp.
        return new History("1.0.0", candidate.instrument(), query.start(), query.end(), "1d",
                "https://finance.sina.com.cn/staticdata/us/" + candidate.instrument().symbol(), null,
                wire.retrievedAt(), false, false, candidate.permissions(), wire.bars(), List.of());
    }
}
