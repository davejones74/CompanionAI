package davejones74.campanionai.llm;

/**
 * Runtime capabilities an {@link LlmProvider} may expose.
 *
 * <p>Values describe what a runtime is able to do, never which vendor or which
 * piece of hardware happens to be behind it. A capability must stay meaningful
 * if the model or the accelerator is swapped out underneath it.
 */
public enum LlmCapability {

    /** Multi-turn chat completion with a {@code system} role. */
    CHAT,

    /** Incremental token delivery through {@link LlmProvider.ChunkHandler}. */
    STREAMING,

    /** Honours a leading {@code system} message. */
    SYSTEM_PROMPT,

    /** Native tool/function calling. */
    TOOL_CALLING,

    /** Native JSON schema or JSON mode output. */
    STRUCTURED_OUTPUT,

    /** Accepts image content parts alongside text. */
    VISION,

    /** Exposes a provider-neutral request flag that enables reasoning output. */
    THINKING,

    /** Supports reading {@code baseUrl} for a usable base URL. */
    MODEL_INFO,

    /** Lists the models the runtime currently has available. */
    MODEL_DISCOVERY,

    /** Exposes a cheap liveness probe. */
    HEALTH_CHECK,

    /** Accepts a context-length override on an individual chat request. */
    PER_REQUEST_CONTEXT_LENGTH
}