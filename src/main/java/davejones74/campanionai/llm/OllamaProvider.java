package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Ollama over its OpenAI-compatible {@code /v1} surface.
 *
 * <p>Ollama-specific behaviour is limited to one field: when
 * {@code campanionai.numCtx} is set, the request carries
 * {@code options.num_ctx}. That override is why this provider advertises
 * {@link LlmCapability#PER_REQUEST_CONTEXT_LENGTH}.
 *
 * <p>{@link LlmCapability#THINKING} is deliberately absent. Ollama's
 * OpenAI-compatible surface exposes no provider-neutral reasoning toggle, so
 * {@code campanionai.llm.think} is not forwarded here. See
 * {@code docs/X1Pro-FastFlowLM-Validation.md} probe A5 for the outstanding
 * per-request context verification on the development host.
 */
public final class OllamaProvider implements LlmProvider {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private static final LlmCapabilities CAPABILITIES = LlmCapabilities.of(
            LlmCapability.CHAT,
            LlmCapability.STREAMING,
            LlmCapability.SYSTEM_PROMPT,
            LlmCapability.TOOL_CALLING,
            LlmCapability.STRUCTURED_OUTPUT,
            LlmCapability.VISION,
            LlmCapability.MODEL_INFO,
            LlmCapability.MODEL_DISCOVERY,
            LlmCapability.HEALTH_CHECK,
            LlmCapability.PER_REQUEST_CONTEXT_LENGTH);

    private final String model;
    private final double temperature;
    private final Integer numCtx;
    private final OpenAiCompatTransport transport;

    public OllamaProvider(String model, String baseUrl, double temperature, Integer numCtx) {
        this(model, baseUrl, temperature, numCtx, new OpenAiCompatTransport(baseUrl, "Ollama"));
    }

    public OllamaProvider(String model,
                          String baseUrl,
                          double temperature,
                          Integer numCtx,
                          OpenAiCompatTransport transport) {
        this.model = model;
        this.temperature = temperature;
        this.numCtx = numCtx;
        this.transport = transport;
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public LlmCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public double temperature() {
        return temperature;
    }

    @Override
    public String baseUrl() {
        return transport.baseUrl();
    }

    @Override
    public String chat(List<LlmMessage> messages) throws LlmException {
        return transport.complete(OpenAiChat.chatCompletionsPath(),
                body(messages, false).toString());
    }

    @Override
    public void chatStream(List<LlmMessage> messages, ChunkHandler handler) throws LlmException {
        transport.stream(OpenAiChat.chatCompletionsPath(), body(messages, true).toString(), handler);
    }

    @Override
    public List<String> listModels() throws LlmException {
        return OpenAiCompatTransport.readModelIds(
                transport.getJson(OpenAiChat.modelsPath()));
    }

    private ObjectNode body(List<LlmMessage> messages, boolean stream) {
        ObjectNode node = OpenAiChat.envelope(
                transport.mapper(), model, messages, temperature, stream);
        if (numCtx != null) {
            node.set("options", transport.mapper().createObjectNode().put("num_ctx", numCtx));
        }
        return node;
    }
}