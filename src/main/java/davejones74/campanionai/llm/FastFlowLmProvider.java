package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * FastFlowLM over its OpenAI-compatible {@code /v1} surface.
 *
 * <p><strong>Validation status: implemented against vendor documentation only.
 * This provider has not been executed against a real FastFlowLM runtime.</strong>
 * Nothing here has been run on the Minisforum X1 Pro HX-370. Passing unit tests
 * demonstrate that this code correctly implements the documented contract; they
 * do not demonstrate that FastFlowLM honours it. The outstanding checks are
 * recorded in {@code docs/X1Pro-FastFlowLM-Validation.md} as probes F1 to F5, and
 * the production model and context budget remain unselected in
 * {@code docs/Qwen-Model-Evaluation.md}.
 *
 * <p>Two request details are documented for FastFlowLM but unconfirmed on
 * hardware, and both are isolated here so a correction touches one method:
 *
 * <ul>
 *   <li>{@code think} is a non-standard request field, defaulted to {@code
 *       false} by {@code campanionai.llm.think}. If the runtime rejects unknown
 *       fields, omit it instead of sending {@code false}. Probe F5 decides.</li>
 *   <li>Context length is fixed when the runtime is started rather than set per
 *       request, so this provider never sends {@code options.num_ctx} and does
 *       not advertise {@link LlmCapability#PER_REQUEST_CONTEXT_LENGTH}. Setting
 *       {@code campanionai.numCtx} against it warns and is ignored.</li>
 * </ul>
 *
 * <p>{@code TOOL_CALLING}, {@code STRUCTURED_OUTPUT} and {@code VISION} are
 * declared because vendor documentation describes them, and are marked
 * unverified for the same reason as the rest of this class. CompanionAI does not
 * currently use any of them.
 */
public final class FastFlowLmProvider implements LlmProvider {

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:52625";

    private static final LlmCapabilities CAPABILITIES = LlmCapabilities.of(
            LlmCapability.CHAT,
            LlmCapability.STREAMING,
            LlmCapability.SYSTEM_PROMPT,
            LlmCapability.TOOL_CALLING,
            LlmCapability.STRUCTURED_OUTPUT,
            LlmCapability.VISION,
            LlmCapability.THINKING,
            LlmCapability.MODEL_INFO,
            LlmCapability.MODEL_DISCOVERY,
            LlmCapability.HEALTH_CHECK);

    private final String model;
    private final double temperature;
    private final boolean think;
    private final OpenAiCompatTransport transport;

    public FastFlowLmProvider(String model, String baseUrl, double temperature, boolean think) {
        this(model, baseUrl, temperature, think,
                new OpenAiCompatTransport(baseUrl, "FastFlowLM"));
    }

    public FastFlowLmProvider(String model,
                              String baseUrl,
                              double temperature,
                              boolean think,
                              OpenAiCompatTransport transport) {
        this.model = model;
        this.temperature = temperature;
        this.think = think;
        this.transport = transport;
    }

    @Override
    public String providerName() {
        return "fastflowlm";
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
    public com.fasterxml.jackson.databind.JsonNode chatMessage(List<LlmMessage> messages,
                                                               java.util.List<com.fasterxml.jackson.databind.JsonNode> tools)
            throws LlmException {
        return transport.completeMessage(OpenAiChat.chatCompletionsPath(),
                body(messages, false, tools).toString());
    }

    @Override
    public List<String> listModels() throws LlmException {
        return OpenAiCompatTransport.readModelIds(
                transport.getJson(OpenAiChat.modelsPath()));
    }

    private ObjectNode body(List<LlmMessage> messages, boolean stream) {
        return body(messages, stream, null);
    }

    private ObjectNode body(List<LlmMessage> messages, boolean stream,
                            java.util.List<com.fasterxml.jackson.databind.JsonNode> tools) {
        ObjectNode node = OpenAiChat.envelope(
                transport.mapper(), model, messages, temperature, stream, tools);
        node.set("think", transport.mapper().getNodeFactory().booleanNode(think));
        return node;
    }
}