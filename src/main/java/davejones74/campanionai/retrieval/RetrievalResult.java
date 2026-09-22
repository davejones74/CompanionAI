package davejones74.campanionai.retrieval;

import java.time.Instant;
import java.util.List;

public record RetrievalResult(
        RetrievalKind kind,
        Instant retrievedAt,
        List<RetrievalItem> items) {

    public RetrievalResult {
        items = List.copyOf(items);
    }
}