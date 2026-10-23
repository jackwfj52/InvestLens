package org.jack.backend.scenario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.scenario.ScenarioData.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {
    private final ScenarioService service;
    public ScenarioController(ScenarioService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<ApiResponse<Result>> create(@Valid @RequestBody Create request) {
        var result = service.create(request);
        return ApiResponses.created(URI.create("/api/scenarios/" + result.scenarioId()), result);
    }
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Result>> get(@PathVariable @Pattern(regexp = ID) String id) { return ApiResponses.ok(service.get(id)); }
}
