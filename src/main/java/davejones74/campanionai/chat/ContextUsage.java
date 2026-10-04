package davejones74.campanionai.chat;

public record ContextUsage(long used, long limit, int percentage,
                           long system, long history, long knowledge, long live, long input) {

    public ContextUsage {
        if (limit < 1) {
            limit = 1;
        }
        if (used < 0) {
            used = 0;
        }
        if (system < 0) system = 0;
        if (history < 0) history = 0;
        if (knowledge < 0) knowledge = 0;
        if (live < 0) live = 0;
        if (input < 0) input = 0;
        if (percentage == 0 && used >= 0 && limit >= 1) {
            percentage = (int) Math.round((used * 100.0) / (double) limit);
        }
        if (percentage < 0) {
            percentage = 0;
        }
        if (percentage > 100) {
            percentage = 100;
        }
    }

    public static ContextUsage of(long used, long limit) {
        return new ContextUsage(used, limit, 0, 0, 0, 0, 0, 0);
    }

    public static ContextUsage of(long used, long limit, long system, long history, long knowledge, long live, long input) {
        long pct = limit > 0 ? (long) Math.round((used * 100.0) / (double) limit) : 0;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return new ContextUsage(used, limit, (int) pct, system, history, knowledge, live, input);
    }
}
