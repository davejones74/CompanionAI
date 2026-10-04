package davejones74.campanionai.retrieval;

public record RetrievalRequest(
        String query,
        RetrievalKind kind,
        String location,
        Freshness freshness,
        int pastDays,
        String context,
        String domain,
        WebSearchProfile profile) {

    public RetrievalRequest {
        query = query == null ? "" : query.trim();
        location = location == null ? null : location.trim();
        if (location != null && location.isEmpty()) location = null;
        context = context == null ? null : context.trim();
        if (context != null && context.isEmpty()) context = null;
        domain = domain == null ? null : domain.trim().toLowerCase(java.util.Locale.ROOT);
        if (domain != null && domain.isEmpty()) domain = null;
        if (profile == null && kind == RetrievalKind.WEB_SEARCH) profile = WebSearchProfile.LOOKUP;
        if (pastDays < 0) pastDays = 0;
        if (pastDays > 92) pastDays = 92;
    }

    public RetrievalRequest(String query, RetrievalKind kind, String location, Freshness freshness) {
        this(query, kind, location, freshness, 0, null);
    }

    public RetrievalRequest(String query, RetrievalKind kind, String location, Freshness freshness,
                            int pastDays, String context) {
        this(query, kind, location, freshness, pastDays, context, null, null);
    }

    public boolean isScoped() {
        return domain != null && !domain.isBlank();
    }
}