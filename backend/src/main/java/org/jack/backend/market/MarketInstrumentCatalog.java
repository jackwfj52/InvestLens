package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import java.util.List;
import static org.jack.backend.market.MarketData.*;

@Component
public class MarketInstrumentCatalog {
    private final List<Candidate> candidates = List.of(
            candidate("STOCK:SINA:NASDAQ:AAPL:USD", "STOCK", "AAPL", "NASDAQ", null,
                    "https://www.nasdaq.com/market-activity/stocks/aapl"),
            candidate("ETF:SINA:NYSE_ARCA:GLD:USD", "ETF", "GLD", "NYSE_ARCA", "GOLD",
                    "https://www.ssga.com/us/en/individual/etfs/spdr-gold-shares-gld"),
            candidate("ETF:SINA:NYSE_ARCA:SLV:USD", "ETF", "SLV", "NYSE_ARCA", "SILVER",
                    "https://www.ishares.com/us/products/239855/ishares-silver-trust-fund"));

    private static Candidate candidate(String id, String type, String symbol, String exchange, String proxy, String source) {
        return new Candidate(new Instrument("1.0.0", id, type, symbol, exchange, "SINA", "USD", "CURRENCY_PER_SHARE",
                "America/New_York", "US_EQUITIES_CORE", "us-core-2026-v1", proxy, Adjustment.UNKNOWN,
                "默认序列按 AKShare 文档为未复权；尚未核实实际窗口复权与公司行动，保持 UNKNOWN"),
                source, "PENDING", false, new Permissions("UNKNOWN", "UNKNOWN", "METADATA_ONLY"));
    }
    public List<Candidate> candidates() { return candidates; }
    public Candidate requireCandidate(String id) {
        return candidates.stream().filter(c -> c.instrument().instrumentId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_INSTRUMENT));
    }
    public Candidate requireResearchEligible(String id) {
        Candidate candidate = requireCandidate(id);
        if (!"ALLOWED".equals(candidate.permissions().localDisplay()) || !"ALLOWED".equals(candidate.permissions().modelInput())) {
            throw new BusinessException(ErrorCode.LICENSE_BLOCKED);
        }
        if (!"QUALIFIED".equals(candidate.qualification()) || !candidate.mainlandAccessVerified()) {
            throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        }
        return candidate;
    }
}
