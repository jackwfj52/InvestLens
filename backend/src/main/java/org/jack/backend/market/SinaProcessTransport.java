package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Component
public class SinaProcessTransport {
    private static final int MAX_BYTES = 262144;
    private final String python, node, script;
    private final boolean enabled;
    private final Semaphore single = new Semaphore(1);
    public SinaProcessTransport(@Value("${investlens.market.sina.python:python}") String python,
                                @Value("${investlens.market.sina.node:node}") String node,
                                @Value("${investlens.market.sina.script:../scripts/market/sina_adapter.py}") String script,
                                @Value("${investlens.market.sina.enabled:false}") boolean enabled) {
        this.python = python; this.node = node; this.script = script; this.enabled = enabled;
    }
    public String exchange(String request) {
        return exchange(request,8000);
    }
    public String exchange(String request,long timeoutMillis) {
        if(timeoutMillis<=0)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis,8000));
        if (!enabled) throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        if (!single.tryAcquire()) throw new BusinessException(ErrorCode.RATE_LIMITED);
        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sina-output"); t.setDaemon(true); return t; });
        try {
            // No shell, URL, credentials, or client-controlled executable parameters.
            process = new ProcessBuilder(python, "-B", script, "--node", node,"--timeout-ms",Long.toString(Math.min(timeoutMillis,8000)))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            Future<byte[]> output = reader.submit(() -> running.getInputStream().readNBytes(MAX_BYTES + 1));
            try (var input = process.getOutputStream()) { input.write(request.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(Math.max(1,deadline-System.nanoTime()), TimeUnit.NANOSECONDS)) throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
            byte[] body = output.get(Math.max(1,deadline-System.nanoTime()), TimeUnit.NANOSECONDS);
            if (body.length > MAX_BYTES || process.exitValue() != 0) throw new BusinessException(ErrorCode.UPSTREAM_ERROR);
            return new String(body, StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
        } catch (TimeoutException error) { throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
        } catch (java.io.IOException error) { throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        } catch (ExecutionException error) { throw new BusinessException(ErrorCode.UPSTREAM_ERROR);
        } finally {
            if (process != null) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            reader.shutdownNow(); single.release();
        }
    }
}
