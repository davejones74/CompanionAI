package davejones74.campanionai.retrieval;

public record RetrievalRequest(
        String query,
        RetrievalKind kind,
        String location,
        Freshness freshness,
        int pastDays,
        String context) {

    public RetrievalRequest {
        query = query == null ? "" : query.trim();
        location = location == null ? null : location.trim();
        if (location != null && location.isEmpty()) location = null;
        context = context == null ? null : context.trim();
        if (context != null && context.isEmpty()) context = null;
        if (pastDays < 0) pastDays = 0;
        if (pastDays > 92) pastDays = 92;
    }

    public RetrievalRequest(String query, RetrievalKind kind, String location, Freshness freshness) {
        this(query, kind, location, freshness, 0, null);
    }
}