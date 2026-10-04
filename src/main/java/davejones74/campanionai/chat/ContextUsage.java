package davejones74.campanionai.chat;

public record ContextUsage(long used, long limit, int percentage) {

    public ContextUsage {
        if (limit < 1) {
            limit = 1;
        }
        if (used < 0) {
            used = 0;
        }
        percentage = (int) Math.round((used * 100.0) / (double) limit);
        if (percentage < 0) {
            percentage = 0;
        }
        if (percentage > 100) {
            percentage = 100;
        }
    }

    public static ContextUsage of(long used, long limit) {
        return new ContextUsage(used, limit, 0);
    }
}
