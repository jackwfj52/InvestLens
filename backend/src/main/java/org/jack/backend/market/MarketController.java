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
    public MarketController(MarketInstrumentCatalog catalog, MarketHistoryTool tool) { this.catalog = catalog; this.tool = tool; }
    @GetMapping("/instruments") public ResponseEntity<ApiResponse<List<Candidate>>> instruments() { return ApiResponses.ok(catalog.candidates()); }
    @GetMapping("/history") public ResponseEntity<ApiResponse<History>> history(@RequestParam String instrumentId,
            @RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponses.ok(tool.execute(new Query(instrumentId, start, end)));
    }
}
