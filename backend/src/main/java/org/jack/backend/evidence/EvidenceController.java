package org.jack.backend.evidence;

import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.evidence.EvidenceData.*;

@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final EvidenceService service;
    public EvidenceController(EvidenceService service) { this.service = service; }
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
