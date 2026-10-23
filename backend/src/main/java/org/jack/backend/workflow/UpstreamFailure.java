package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class UpstreamFailure extends BusinessException {
    private final boolean retryable;
    private final long retryAfterMillis;
    public UpstreamFailure(ErrorCode code, boolean retryable, long retryAfterMillis) {
        super(code);this.retryable=retryable;this.retryAfterMillis=Math.max(0,retryAfterMillis);
    }
    public boolean retryable(){return retryable;}
    public long retryAfterMillis(){return retryAfterMillis;}
    public static long retryAfter(String header, Clock clock) {
        if(header==null)return 0;
        if(header.trim().matches("[0-9]+")) {
            try{return Math.multiplyExact(Long.parseLong(header.trim()),1000);}
            catch(ArithmeticException | NumberFormatException overflow){return Long.MAX_VALUE;}
        }
        try{return Math.multiplyExact(Math.max(0,Long.parseLong(header.trim())),1000);}
        catch(RuntimeException ignored){
            try{return Math.max(0,Duration.between(clock.instant(),ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());}
            catch(RuntimeException invalid){return 0;}
        }
    }
}
