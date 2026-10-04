package davejones74.campanionai.retrieval;

/**
 * Optional progress reporting for a live lookup, used to surface what the assistant is doing
 * while it waits for an external service.
 *
 * <p>Not for logging. Implementations write to the user's stream; anything that throws a
 * {@link RuntimeException} aborts the request, which is how a disconnected client is noticed.
 */
@FunctionalInterface
public interface RetrievalProgress {

    RetrievalProgress NOOP = message -> {
    };

    void onProgress(String message);
}