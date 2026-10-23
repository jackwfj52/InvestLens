package org.jack.backend.workflow;

import jakarta.annotation.PreDestroy;
import org.jack.backend.common.exception.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RunEventStreams {
    private final RunEventService events;
    private final Semaphore permits=new Semaphore(32);
    private final ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(2,task->{var thread=new Thread(task,"run-event-stream");thread.setDaemon(true);return thread;});
    private final Set<Connection> connections=ConcurrentHashMap.newKeySet();
    public RunEventStreams(RunEventService events){this.events=events;executor.setRemoveOnCancelPolicy(true);}
    public SseEmitter open(String id,long after) {
        events.read(id,after);
        if(!permits.tryAcquire())throw new BusinessException(ErrorCode.RATE_LIMITED);
        var connection=new Connection(id,after);connections.add(connection);
        connection.emitter.onCompletion(connection::close);connection.emitter.onTimeout(connection::close);connection.emitter.onError(failure->connection.close());
        connection.future=executor.scheduleWithFixedDelay(connection::send,0,500,TimeUnit.MILLISECONDS);
        if(connection.closed.get())connection.future.cancel(false);
        return connection.emitter;
    }
    private final class Connection {
        final String id;
        final SseEmitter emitter=new SseEmitter(30_000L);
        final AtomicBoolean closed=new AtomicBoolean();
        volatile ScheduledFuture<?> future;
        long cursor;
        int ticks;
        Connection(String id,long cursor){this.id=id;this.cursor=cursor;}
        void send() {
            if(closed.get())return;
            try {
                var batch=events.read(id,cursor);
                for(var event:batch.page().items()) {
                    if(closed.get())return;
                    emitter.send(SseEmitter.event().id(event.eventId()).name("progress").data(event,MediaType.APPLICATION_JSON));cursor=event.sequence();
                }
                if(batch.finished()){close();return;}
                if(ticks++%20==0)emitter.send(SseEmitter.event().comment("keep-alive"));
            }catch(Exception failure){close();}
        }
        void close(){if(closed.compareAndSet(false,true)){if(future!=null)future.cancel(false);connections.remove(this);permits.release();emitter.complete();}}
    }
    @PreDestroy public void shutdown(){connections.forEach(Connection::close);executor.shutdownNow();}
}
