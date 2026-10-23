package org.jack.backend.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static org.jack.backend.common.validation.ContractPatterns.ID;

@RestController
@RequestMapping("/api")
public class RunController {
    private final RunCreationService creation;
    private final WorkflowStore store;
    public RunController(RunCreationService creation, WorkflowStore store) { this.creation=creation;this.store=store; }
    @PostMapping("/decisions/{id}/runs")
    public ResponseEntity<ApiResponse<RunDtos.View>> create(@PathVariable @Pattern(regexp=ID) String id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_.:-]{1,128}") String key, @Valid @RequestBody RunDtos.Create request) {
        var run=creation.create(id,key,request);
        return ApiResponses.accepted(URI.create("/api/runs/"+run.runId()),RunDtos.view(run));
    }
    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<RunDtos.View>> get(@PathVariable @Pattern(regexp=ID) String id) { return ApiResponses.ok(RunDtos.view(store.get(id))); }
    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<ApiResponse<RunDtos.View>> cancel(@PathVariable @Pattern(regexp=ID) String id) { return ApiResponses.ok(RunDtos.view(store.cancel(id))); }
}
