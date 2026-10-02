package davejones74.campanionai.llm;

/**
 * Signals that a chat request could not be completed.
 *
 * <p>Replaces {@code LlmClient.LlmException}. Callers treat this as a runtime
 * failure and fall back to rule-based replies rather than surfacing an error to
 * the user.
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}