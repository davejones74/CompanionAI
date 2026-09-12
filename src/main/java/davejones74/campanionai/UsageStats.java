package davejones74.campanionai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicLong;

public final class UsageStats {
    private final long startedAt = System.currentTimeMillis();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong streamed = new AtomicLong();
    private final AtomicLong jsonReplies = new AtomicLong();
    private final AtomicLong offline = new AtomicLong();
    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong fetchErrors = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    private long latencySumMs;
    private long latencyCount;
    private volatile long lastLatencyMs;
    private volatile long lastOutputTokens;
    private final Object latencyLock = new Object();

    public void recordStart() {
        total.incrementAndGet();
    }

    public void recordStreamed() {
        streamed.incrementAndGet();
    }

    public void recordJsonReply() {
        jsonReplies.incrementAndGet();
    }

    public void recordOffline() {
        offline.incrementAndGet();
    }

    public void recordFetch() {
        fetches.incrementAndGet();
    }

    public void recordFetchError() {
        fetchErrors.incrementAndGet();
    }

    public void addInputTokens(long n) {
        inputTokens.addAndGet(n);
    }

    public void addOutputTokens(long n) {
        outputTokens.addAndGet(n);
    }

    public void recordLatency(long elapsedMs, long outTokens) {
        synchronized (latencyLock) {
            latencySumMs += elapsedMs;
            latencyCount++;
        }
        lastLatencyMs = elapsedMs;
        lastOutputTokens = outTokens;
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public String toJson() {
        synchronized (latencyLock) {
            ObjectMapper m = new ObjectMapper();
            try {
                return m.writeValueAsString(m.createObjectNode()
                        .put("uptimeS", uptimeMs() / 1000)
                        .put("total", total.get())
                        .put("streamed", streamed.get())
                        .put("jsonReplies", jsonReplies.get())
                        .put("offline", offline.get())
                        .put("urlFetches", fetches.get())
                        .put("fetchErrors", fetchErrors.get())
                        .put("inputTokens", inputTokens.get())
                        .put("outputTokens", outputTokens.get())
                        .put("avgLatencyMs", latencyCount == 0 ? 0 : latencySumMs / latencyCount)
                        .put("lastLatencyMs", lastLatencyMs)
                        .put("lastOutputTokens", lastOutputTokens));
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}