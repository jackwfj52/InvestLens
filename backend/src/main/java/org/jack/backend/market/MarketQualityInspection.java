package org.jack.backend.market;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.jack.backend.market.MarketData.*;
import static org.jack.backend.market.MarketQuality.*;

@Component
public class MarketQualityInspection {
    private final MarketInstrumentCatalog catalog;
    private final MarketDataProvider provider;
    private final MarketQualityService quality;
    private final UsEquitiesCalendar calendar;
    public MarketQualityInspection(MarketInstrumentCatalog catalog, MarketDataProvider provider, MarketQualityService quality, UsEquitiesCalendar calendar) {
        this.catalog = catalog; this.provider = provider; this.quality = quality; this.calendar = calendar;
    }
    public UsEquitiesCalendar.Definition calendar() { return calendar.definition(); }
    public Result inspect(Query query) {
        MarketWindow.validate(query, Clock.systemUTC());
        Candidate candidate = catalog.requireCandidate(query.instrumentId());
        if (!"QUALIFIED".equals(candidate.qualification()) || !candidate.mainlandAccessVerified()
                || !"ALLOWED".equals(candidate.permissions().localDisplay()) || !"ALLOWED".equals(candidate.permissions().modelInput())) {
            var issues = new ArrayList<Issue>();
            if (!"ALLOWED".equals(candidate.permissions().localDisplay()) || !"ALLOWED".equals(candidate.permissions().modelInput())) issues.add(new Issue("LICENSE_BLOCKED", null, true));
            if (!candidate.mainlandAccessVerified()) issues.add(new Issue("MAINLAND_ACCESS_UNVERIFIED", null, true));
            if (!"QUALIFIED".equals(candidate.qualification())) issues.add(new Issue("SOURCE_NOT_QUALIFIED", null, true));
            if (candidate.instrument().adjustment() == Adjustment.UNKNOWN) issues.add(new Issue("ADJUSTMENT_UNKNOWN", null, true));
            return new Result("1.0.0", VERSION, issues.stream().anyMatch(i -> i.code().equals("LICENSE_BLOCKED")) ? Quality.LICENSE_BLOCKED : Quality.INVALID,
                    false, ReturnBasis.UNKNOWN, candidate.instrument(), query.start(), query.end(), null, null, null,
                    0, 0, 0, 0, List.of(), issues, List.of());
        }
        History history = provider.history(query);
        MarketWindow.requireMatching(query, history, candidate);
        return quality.assess(history, Instant.now());
    }
}
