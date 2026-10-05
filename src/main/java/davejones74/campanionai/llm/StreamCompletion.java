package davejones74.campanionai.llm;

/**
 * How a streaming chat completion ended, as reported by the runtime.
 *
 * <p>{@code finishReason} is the OpenAI {@code finish_reason} of the final chunk
 * ({@code "stop"}, {@code "length"}, {@code "cancel"}, or {@code null} when the
 * runtime never sent one). {@code completionTokens} comes from the {@code usage}
 * node when the runtime sends it, and is {@code null} otherwise; it is a real
 * token count from the provider, not an estimate.
 */
public record StreamCompletion(String finishReason, Integer completionTokens) {

    /** Reached the requested {@code max_tokens} output limit. A controlled outcome, not a failure. */
    public boolean reachedOutputLimit() {
        return "length".equals(finishReason);
    }

    /** The runtime cancelled generation, typically because its caller went away. */
    public boolean cancelled() {
        return "cancel".equals(finishReason);
    }
}
