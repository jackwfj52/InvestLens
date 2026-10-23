package org.jack.backend.tool;

import org.jack.backend.market.*;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import static org.jack.backend.market.MarketData.*;

/** Fixed tool parameters only; no model-supplied URL or source substitution. */
@Component
public class MarketHistoryTool {
    private final MarketDataProvider provider;
    private final MarketInstrumentCatalog catalog;
    private final MarketQualityService quality;
    public MarketHistoryTool(MarketDataProvider provider, MarketInstrumentCatalog catalog, MarketQualityService quality) { this.provider = provider; this.catalog = catalog; this.quality = quality; }
    public MarketQuality.Result execute(Query query) {
        MarketWindow.validate(query, Clock.systemUTC());
        Candidate candidate = catalog.requireResearchEligible(query.instrumentId());
        History history = provider.history(query);
        MarketWindow.requireMatching(query, history, candidate);
        MarketQuality.Result result = quality.assess(history, Instant.now());
        if (!result.calculationAllowed()) throw new BusinessException(ErrorCode.DATA_UNUSABLE,
                result.issues().stream().filter(MarketQuality.Issue::blocking).map(i -> new ApiError("market", i.code(), "行情未通过质量门槛")).toList());
        return result;
    }
}
