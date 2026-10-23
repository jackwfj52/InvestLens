package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class RunEventService {
    public record View(String schemaVersion,String eventId,String runId,long sequence,EventType type,Stage stage,RunStatus status,String createdAt,String message) { }
    public record Page(List<View> items,long nextSequence) { }
    public record Batch(Page page,boolean finished) { }
    private final WorkflowStore store;
    private final WorkflowMapper mapper;
    private final CurrentOwner owner;
    public RunEventService(WorkflowStore store,WorkflowMapper mapper,CurrentOwner owner){this.store=store;this.mapper=mapper;this.owner=owner;}
    @Transactional(readOnly=true)
    public Batch read(String id,long after) {
        var run=store.get(id);
        if(after<0 || after>run.eventSequence())throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var items=mapper.eventsAfter(id,owner.id(),after).stream().map(event->new View("1.0.0",event.eventId(),id,event.sequence(),event.type(),event.stage(),event.status(),RunDtos.time(event.createdAt()),event.message())).toList();
        long next=items.isEmpty()?after:items.get(items.size()-1).sequence();
        return new Batch(new Page(items,next),run.status().terminal() && next==run.eventSequence());
    }
}
