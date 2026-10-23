package org.jack.backend.quant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.quant.MetricData.*;

@RestController
@RequestMapping("/api")
public class MetricController {
    private final MetricService service;
    private final QuantPolicy policy;
    public MetricController(MetricService service, QuantPolicy policy) { this.service = service; this.policy = policy; }
    @PostMapping("/metric-bundles")
    public ResponseEntity<ApiResponse<Bundle>> calculate(@Valid @RequestBody Create request) {
        Bundle bundle = service.calculate(request);
        return ApiResponses.created(URI.create("/api/metric-bundles/" + bundle.bundleId()), bundle);
    }
    @GetMapping("/metric-bundles/{id}")
    public ResponseEntity<ApiResponse<Bundle>> get(@PathVariable @Pattern(regexp = ID) String id) { return ApiResponses.ok(service.get(id)); }
    @GetMapping("/metric-policy")
    public ResponseEntity<ApiResponse<QuantPolicy.Definition>> policy() { return ApiResponses.ok(policy.definition()); }
}
