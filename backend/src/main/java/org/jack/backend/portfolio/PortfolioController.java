package org.jack.backend.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.api.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.portfolio.PortfolioDtos.*;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {
    private final PortfolioService service;
    public PortfolioController(PortfolioService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<Snapshot>> create(@Valid @RequestBody Create request) {
        Snapshot result = service.create(request);
        return ApiResponses.created(URI.create("/api/portfolios/" + result.portfolioId() + "?version=1"), result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Snapshot>> get(@Pattern(regexp = ID) @PathVariable String id,
                                                   @Min(1) @RequestParam(required = false) Integer version) {
        return ApiResponses.ok(service.get(id, version));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<Snapshot>> revise(@Pattern(regexp = ID) @PathVariable String id,
                                                      @Valid @RequestBody Revision request) {
        return ApiResponses.ok(service.revise(id, request));
    }
}
