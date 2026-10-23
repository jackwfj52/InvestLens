package org.jack.backend.market;

import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.api.ApiResponses;
import org.jack.backend.tool.MarketHistoryTool;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import static org.jack.backend.market.MarketData.*;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketInstrumentCatalog catalog;
    private final MarketHistoryTool tool;
    private final MarketQualityInspection inspection;
    public MarketController(MarketInstrumentCatalog catalog, MarketHistoryTool tool, MarketQualityInspection inspection) { this.catalog = catalog; this.tool = tool; this.inspection = inspection; }
    @GetMapping("/instruments") public ResponseEntity<ApiResponse<List<Candidate>>> instruments() { return ApiResponses.ok(catalog.candidates()); }
    @GetMapping("/history") public ResponseEntity<ApiResponse<MarketQuality.Result>> history(@RequestParam String instrumentId,
            @RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponses.ok(tool.execute(new Query(instrumentId, start, end)));
    }
    @GetMapping("/quality") public ResponseEntity<ApiResponse<MarketQuality.Result>> quality(@RequestParam String instrumentId,
            @RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponses.ok(inspection.inspect(new Query(instrumentId, start, end)));
    }
    @GetMapping("/quality-policy") public ResponseEntity<ApiResponse<UsEquitiesCalendar.Definition>> policy() { return ApiResponses.ok(inspection.calendar()); }
}
