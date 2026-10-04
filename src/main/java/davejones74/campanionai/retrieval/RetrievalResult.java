package davejones74.campanionai.retrieval;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one lookup, including enough metadata for the model to describe what it
 * actually did.
 *
 * <p>{@code domain}, {@code queries} and {@code pagesFetched} exist so that the prompt can state
 * the scope, the searches run and the amount of evidence obtained. Without them the model has
 * no way to distinguish "the searches returned nothing" from "the website has nothing", and
 * answers accordingly.
 */
public record RetrievalResult(
        RetrievalKind kind,
        Instant retrievedAt,
        List<RetrievalItem> items,
        String domain,
        List<String> queries,
        int pagesFetched) {

    public RetrievalResult {
        items = List.copyOf(items);
        queries = List.copyOf(queries);
        domain = domain == null || domain.isBlank() ? null : domain;
        if (pagesFetched < 0) pagesFetched = 0;
    }

    public RetrievalResult(RetrievalKind kind, Instant retrievedAt, List<RetrievalItem> items) {
        this(kind, retrievedAt, items, null, List.of(), 0);
    }

    public int resultsReturned() {
        return items.size();
    }
}