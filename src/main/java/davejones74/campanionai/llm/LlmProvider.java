package davejones74.campanionai.llm;

import java.util.List;

/**
 * Application-facing abstraction over an LLM runtime.
 *
 * <p>Application and retrieval code depends only on this interface. Runtime
 * identity, capability differences and runtime-specific request fields are the
 * concern of implementations.
 */
public interface LlmProvider {

    /** Short runtime identifier, for example {@code ollama} or {@code fastflowlm}. */
    String providerName();

    LlmCapabilities capabilities();

    /** Model identifier sent on every request. */
    String model();

    /** Sampling temperature sent on every request. */
    double temperature();

    /** Base URL this provider was configured with. */
    String baseUrl();

    /** Returns the assistant's complete reply, trimmed. */
    String chat(List<LlmMessage> messages) throws LlmException;

    /** Delivers reply deltas to {@code handler} as they arrive. */
    void chatStream(List<LlmMessage> messages, ChunkHandler handler) throws LlmException;

    /**
     * Lists the model identifiers this runtime currently has available.
     *
     * @throws LlmException if the runtime is unreachable
     * @throws UnsupportedOperationException if {@link LlmCapability#MODEL_DISCOVERY}
     *         is absent from {@link #capabilities()}
     */
    default List<String> listModels() throws LlmException {
        if (!capabilities().has(LlmCapability.MODEL_DISCOVERY)) {
            throw new UnsupportedOperationException(
                    providerName() + " does not support model discovery");
        }
        throw new UnsupportedOperationException(
                providerName() + " has not implemented model discovery");
    }

    @FunctionalInterface
    interface ChunkHandler {
        void onDelta(String delta);
    }
}