package org.jack.backend.decision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.api.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

import static org.jack.backend.decision.DecisionDtos.*;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final DecisionService service;

    public DecisionController(DecisionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<Version>> create(@Valid @RequestBody Draft draft) {
        Version result = service.create(draft);
        return ApiResponses.created(URI.create("/api/decisions/" + result.decisionId() + "?version=1"), result);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<Version>> revise(@Pattern(regexp = ID) @PathVariable String id,
                                                       @Valid @RequestBody Revision request) {
        return ApiResponses.ok(service.revise(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Version>> get(@Pattern(regexp = ID) @PathVariable String id,
                                                    @Min(1) @RequestParam(required = false) Integer version) {
        return ApiResponses.ok(service.get(id, version));
    }
}
