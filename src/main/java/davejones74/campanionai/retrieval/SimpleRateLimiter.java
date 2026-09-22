package davejones74.campanionai.retrieval;

import java.time.LocalDate;

public final class SimpleRateLimiter {

    private final int maxPerDay;
    private final Object lock = new Object();
    private LocalDate day;
    private int count;

    public SimpleRateLimiter(int maxPerDay) {
        this.maxPerDay = Math.max(1, maxPerDay);
    }

    public void acquire() throws RetrievalException {
        synchronized (lock) {
            LocalDate today = LocalDate.now();
            if (!today.equals(day)) {
                day = today;
                count = 0;
            }
            if (count >= maxPerDay) {
                throw new RetrievalException("Daily request limit reached",
                        "I've hit my daily limit for that service, so I can't check right now.");
            }
            count++;
        }
    }
}