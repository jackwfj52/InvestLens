package org.jack.backend.report;

import jakarta.validation.constraints.*;
import org.jack.backend.common.api.*;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.workflow.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.jack.backend.common.validation.ContractPatterns.ID;

@RestController
@RequestMapping("/api/decisions")
public class HistoryController {
    public record Page<T>(List<T> items,String nextCursor) { }
    public record Summary(String decisionId,int version,String inputText,String inputStatus,String updatedAt,String latestRunId) { }
    private record Cursor(LocalDateTime time,String id) { }
    private final HistoryMapper history;
    private final CurrentOwner owner;
    private final DecisionService decisions;
    private final WorkflowStore store;
    private final ObjectMapper json;
    public HistoryController(HistoryMapper history,CurrentOwner owner,DecisionService decisions,WorkflowStore store,ObjectMapper json){this.history=history;this.owner=owner;this.decisions=decisions;this.store=store;this.json=json;}
    @GetMapping
    public ResponseEntity<ApiResponse<Page<Summary>>> decisions(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) {
        var after=cursor(cursor);var rows=history.decisions(owner.id(),after.time(),after.id(),limit+1);var items=new ArrayList<Summary>();
        for(var row:rows.subList(0,Math.min(limit,rows.size()))) {
            var body=json.readTree(row.body());String text=body.get("inputText").asString();
            items.add(new Summary(row.decisionId(),body.get("version").asInt(),text.substring(0,Math.min(text.length(),160)),body.get("inputStatus").asString(),time(row.updatedAt()),row.latestRunId()));
        }
        String next=rows.size()>limit?encode(rows.get(limit-1).createdAt(),rows.get(limit-1).decisionId()):null;
        return ApiResponses.ok(new Page<>(items,next));
    }
    @GetMapping("/{id}/runs")
    public ResponseEntity<ApiResponse<Page<RunDtos.View>>> runs(@PathVariable @Pattern(regexp=ID) String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) {
        decisions.get(id,null);var after=cursor(cursor);var ids=history.runs(owner.id(),id,after.time(),after.id(),limit+1);var items=ids.subList(0,Math.min(limit,ids.size())).stream().map(store::get).map(RunDtos::view).toList();
        var last=items.isEmpty()?null:store.get(items.get(items.size()-1).runId());
        return ApiResponses.ok(new Page<>(items,ids.size()>limit?encode(last.createdAt(),last.runId()):null));
    }
    private static Cursor cursor(String value) {
        if(value==null)return new Cursor(null,null);
        try {
            if(value.length()>500 || !value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            String decoded=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);String[] parts=decoded.split("\\|",-1);
            if(parts.length!=2 || !parts[1].matches(ID))throw new IllegalArgumentException();
            LocalDateTime time=LocalDateTime.parse(parts[0]);if(!encode(time,parts[1]).equals(value))throw new IllegalArgumentException();
            return new Cursor(time,parts[1]);
        }catch(RuntimeException invalid){throw new BusinessException(ErrorCode.INVALID_REQUEST);}
    }
    private static String encode(LocalDateTime time,String id){return Base64.getUrlEncoder().withoutPadding().encodeToString((time+"|"+id).getBytes(StandardCharsets.UTF_8));}
    private static String time(LocalDateTime time){return time.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'"));}
}
