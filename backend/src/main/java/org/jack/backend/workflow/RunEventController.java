package org.jack.backend.workflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.jack.backend.common.api.ApiResponses;
import org.jack.backend.common.exception.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static org.jack.backend.common.validation.ContractPatterns.ID;

@RestController
@RequestMapping("/api/runs/{id}/events")
public class RunEventController {
    private final RunEventService events;
    private final RunEventStreams streams;
    public RunEventController(RunEventService events,RunEventStreams streams){this.events=events;this.streams=streams;}
    @GetMapping
    public ResponseEntity<?> get(@PathVariable @Pattern(regexp=ID) String id,@RequestParam(defaultValue="0") String afterSequence,HttpServletRequest request) {
        long after=parse(afterSequence);
        if(request.getHeader("Accept")!=null && request.getHeader("Accept").contains(MediaType.TEXT_EVENT_STREAM_VALUE))
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control","no-cache, no-transform").header("X-Accel-Buffering","no").body(streams.open(id,after));
        return ApiResponses.ok(events.read(id,after).page());
    }
    static long parse(String value){try{if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}catch(RuntimeException failure){throw new BusinessException(ErrorCode.INVALID_REQUEST);}}
}
