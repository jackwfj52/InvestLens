package org.jack.backend.report;

import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.*;
import org.jack.backend.workflow.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static org.jack.backend.common.validation.ContractPatterns.ID;

@RestController
@RequestMapping("/api/runs/{id}")
public class ReportController {
    private final ReportService reports;
    private final WorkflowStore store;
    public ReportController(ReportService reports,WorkflowStore store){this.reports=reports;this.store=store;}
    @GetMapping("/report")
    public ResponseEntity<? extends ApiResponse<?>> get(@PathVariable @Pattern(regexp=ID) String id) {
        var run=store.get(id);
        return run.status().terminal()?ApiResponses.ok(reports.get(id)):ApiResponses.inProgress(RunDtos.view(run));
    }
    @GetMapping("/report/figures")
    public ResponseEntity<? extends ApiResponse<?>> figures(@PathVariable @Pattern(regexp=ID) String id){return ApiResponses.ok(reports.figures(id));}
    @GetMapping("/evidence")
    public ResponseEntity<ApiResponse<ReportService.EvidenceBoard>> evidence(@PathVariable @Pattern(regexp=ID) String id){return ApiResponses.ok(reports.evidence(id));}
}
