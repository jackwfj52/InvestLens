package org.jack.backend.evidence;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import org.jack.backend.portfolio.PortfolioService;
import org.jack.backend.market.MarketData.Query;
import java.net.URI;
import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.evidence.EvidenceData.*;

@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final EvidenceService service;
    private final PortfolioService portfolios;
    public EvidenceController(EvidenceService service, PortfolioService portfolios) { this.service = service; this.portfolios = portfolios; }
    @PostMapping("/calculation-snapshots")
    public ResponseEntity<ApiResponse<Snapshot>> freeze(@Valid @RequestBody CalculationSnapshotRequest request) {
        var portfolio = portfolios.getSnapshot(request.portfolioSnapshotId());
        var snapshot = service.freeze(portfolio.snapshotId(), request.marketInputs().stream()
                .map(w -> new Query(w.instrumentId(), w.start(), w.end())).toList(), portfolio.dataMode());
        return ApiResponses.created(URI.create("/api/calculation-snapshots/" + snapshot.snapshotId()), snapshot);
    }
    @GetMapping("/evidence/{id}")
    public ResponseEntity<ApiResponse<Evidence>> evidence(@PathVariable @Pattern(regexp = ID) String id) {
        return ApiResponses.ok(service.evidence(id));
    }
    @GetMapping("/calculation-snapshots/{id}")
    public ResponseEntity<ApiResponse<Snapshot>> snapshot(@PathVariable @Pattern(regexp = ID) String id) {
        return ApiResponses.ok(service.snapshot(id));
    }
    @GetMapping("/calculation-snapshots/{id}/inputs")
    public ResponseEntity<ApiResponse<Replay>> replay(@PathVariable @Pattern(regexp = ID) String id) {
        return ApiResponses.ok(service.replay(id));
    }
}
