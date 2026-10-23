package org.jack.backend.workbench;

import org.jack.backend.support.CalculationIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;

/** Opt-in SYNTHETIC persisted result for exercising the ordinary read-only browser UI. */
@EnabledIfSystemProperty(named = "browser.fixture", matches = "true|clean")
class WorkbenchBrowserFixture extends CalculationIntegrationSupport {
    @EnabledIfSystemProperty(named = "browser.fixture", matches = "true")
    @Test void seedShortSampleForBrowser() throws Exception {
        Path exported = Path.of("target/g15-browser-fixture.json");
        if (Files.exists(exported)) {
            String id = json.readTree(Files.readString(exported)).get("bundleId").asString();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM metric_bundles WHERE bundle_id = ?", Integer.class, id) != 0)
                throw new IllegalStateException("Clean the previous browser fixture before seeding again");
        }
        var p = portfolio();
        var s = snapshot(p, List.of(history(3, "PAYLOAD_ALLOWED")));
        var b = calculate(s);
        Files.writeString(Path.of("target/g15-browser-fixture.json"), json.writeValueAsString(Map.of(
                "portfolioId", p.portfolioId(), "snapshotId", s.snapshotId(), "bundleId", b.bundleId(),
                "evidenceIds", s.marketInputs().stream().map(i -> i.evidenceRef()).toList())));
    }
    @EnabledIfSystemProperty(named = "browser.fixture", matches = "clean")
    @Test void cleanExportedFixtureOnly() throws Exception {
        var saved = json.readTree(Files.readString(Path.of("target/g15-browser-fixture.json")));
        var p = portfolios.get(saved.get("portfolioId").asString(), 1);
        if (p.dataMode() != org.jack.backend.portfolio.PortfolioDtos.DataMode.SYNTHETIC)
            throw new IllegalStateException("Cleanup refuses non-synthetic data");
        portfolioIds.add(p.portfolioId()); snapshotIds.add(saved.get("snapshotId").asString()); bundleIds.add(saved.get("bundleId").asString());
        saved.get("evidenceIds").forEach(id -> evidenceIds.add(id.asString()));
        super.cleanCalculationRows();
    }
    // The explicit flag leaves only this fixture's IDs for browser verification and targeted cleanup.
    @Override protected void cleanCalculationRows() { }
}
