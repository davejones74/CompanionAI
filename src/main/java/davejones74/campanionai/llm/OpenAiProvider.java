package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * A plain OpenAI-compatible endpoint, used as the second attempt when the local runtime is down.
 *
 * <p>Deliberately the least capable provider in the package. It carries no vendor quirks, no
 * per-request context override and no reasoning toggle, and it advertises neither tool calling
 * nor vision. That is not an oversight about the API; it is the point. This provider only ever
 * runs when the local model has already failed, and the request it receives has been narrowed to
 * the current question (see {@link CloudFallback}). Advertising tool calling here would invite the
 * application to hand a cloud model the local file-writing tools, which is a larger disclosure
 * than a chat message and buys nothing, because the tools are executed locally anyway.
 *
 * <p>The base URL is the host root. {@code /v1} is appended per request, so both
 * {@code https://api.openai.com} and the frequently pasted {@code https://api.openai.com/v1}
 * resolve to the same endpoint rather than to {@code /v1/v1}.
 */
public final class OpenAiProvider implements LlmProvider {

    /** Hosted OpenAI's base URL, without the version segment that requests append. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private static final LlmCapabilities CAPABILITIES = LlmCapabilities.of(
            LlmCapability.CHAT,
            LlmCapability.STREAMING,
            LlmCapability.SYSTEM_PROMPT,
            LlmCapability.STRUCTURED_OUTPUT);

    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final OpenAiCompatTransport transport;

    public OpenAiProvider(String model, String baseUrl, String apiKey, double temperature) {
        this(model, baseUrl, apiKey, temperature, LlmProviderFactory.DEFAULT_MAX_TOKENS);
    }

    public OpenAiProvider(String model, String baseUrl, String apiKey, double temperature,
                          int maxTokens) {
        this(model, temperature, maxTokens,
                new OpenAiCompatTransport(withoutVersionSegment(baseUrl), "Cloud model", apiKey));
    }

    OpenAiProvider(String model, double temperature, OpenAiCompatTransport transport) {
        this(model, temperature, LlmProviderFactory.DEFAULT_MAX_TOKENS, transport);
    }

    OpenAiProvider(String model, double temperature, int maxTokens, OpenAiCompatTransport transport) {
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.transport = transport;
    }

    @Override
    public String providerName() {
        return "openai";
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
    public int maxTokens() {
        return maxTokens;
    }

    @Override
    public String baseUrl() {
        return transport.baseUrl();
    }

    @Override
    public String chat(List<LlmMessage> messages) throws LlmException {
        return transport.complete(OpenAiChat.chatCompletionsPath(), body(messages, false).toString());
    }

    @Override
    public void chatStream(List<LlmMessage> messages, ChunkHandler handler) throws LlmException {
        transport.stream(OpenAiChat.chatCompletionsPath(), body(messages, true).toString(), handler);
    }

    @Override
    public List<String> listModels() throws LlmException {
        return OpenAiCompatTransport.readModelIds(transport.getJson(OpenAiChat.modelsPath()));
    }

    private ObjectNode body(List<LlmMessage> messages, boolean stream) {
        return OpenAiChat.envelope(transport.mapper(), model, messages, temperature, stream,
                null, maxTokens);
    }

    /** Drops a trailing {@code /v1} so that a pasted versioned URL does not double up. */
    private static String withoutVersionSegment(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - "/v1".length());
        }
        return value;
    }
}