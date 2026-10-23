package org.jack.backend.tool;

import org.jack.backend.market.*;
import org.springframework.stereotype.Component;
import java.time.Clock;
import static org.jack.backend.market.MarketData.*;

/** Fixed tool parameters only; no model-supplied URL or source substitution. */
@Component
public class MarketHistoryTool {
    private final MarketDataProvider provider;
    private final MarketInstrumentCatalog catalog;
    public MarketHistoryTool(MarketDataProvider provider, MarketInstrumentCatalog catalog) { this.provider = provider; this.catalog = catalog; }
    public History execute(Query query) {
        MarketWindow.validate(query, Clock.systemUTC());
        catalog.requireResearchEligible(query.instrumentId());
        return provider.history(query);
    }
}
