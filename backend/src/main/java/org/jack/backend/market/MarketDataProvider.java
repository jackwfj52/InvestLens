package org.jack.backend.market;

import static org.jack.backend.market.MarketData.*;

/** Internal SPI; raw history never bypasses the research permission and quality gates. */
public interface MarketDataProvider {
    History history(Query query);
    History history(Query query,long timeoutMillis);
}
