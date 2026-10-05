package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level tests for {@link FastFlowLmProvider}.
 *
 * <p>These assert the bytes CompanionAI puts on the wire, which is the part of the contract a
 * real runtime either honours or rejects. The response parsing itself is covered by
 * {@link OpenAiCompatTransportTest}; what matters here is the request shape, in particular the
 * two details that differ from Ollama:
 *
 * <ul>
 *   <li>{@code think} is FastFlowLM-specific and has no Ollama equivalent, so it is always sent
 *       explicitly rather than being left to a default.</li>
 *   <li>{@code options.num_ctx} is deliberately absent, because FastFlowLM fixes the context
 *       budget when the runtime starts and rejects or ignores a per-request override.</li>
 * </ul>
 *
 * <p>Passing these tests does not prove a FastFlowLM runtime accepts the body. That was measured
 * on the Minisforum X1 Pro and is recorded in {@code docs/X1Pro-FastFlowLM-Validation.md}.
 */
class FastFlowLmProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final List<LlmMessage> MESSAGES = List.of(
            new LlmMessage("system", "be terse"),
            new LlmMessage("user", "hello"));

    private static String completion(String content) {
        ObjectNode message = MAPPER.createObjectNode().put("content", content);
        ObjectNode choice = MAPPER.createObjectNode().set("message", message);
        return MAPPER.createObjectNode()
                .set("choices", MAPPER.createArrayNode().add(choice))
                .toString();
    }

    private static String delta(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void postsToTheOpenAiCompatibleChatCompletionsPath() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false).chat(MESSAGES);

            assertEquals(CHAT_PATH, s.lastPath());
            assertEquals("qwen2.5-it:3b", s.lastRequest().path("model").asText());
            assertEquals(0.7, s.lastRequest().path("temperature").asDouble(), 1e-9);
            assertEquals("system", s.lastRequest().path("messages").get(0).path("role").asText());
            assertEquals("hello", s.lastRequest().path("messages").get(1).path("content").asText());
        }
    }

    @Test
    void sendsThinkFalseExplicitlyRatherThanOmittingIt() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false).chat(MESSAGES);

            JsonNode think = s.lastRequest().path("think");
            assertTrue(think.isBoolean(), "think must be sent as a boolean, not omitted");
            assertFalse(think.asBoolean());
        }
    }

    @Test
    void sendsThinkTrueWhenEnabled() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, true).chat(MESSAGES);

            assertTrue(s.lastRequest().path("think").asBoolean());
        }
    }

    @Test
    void neverSendsOllamaStyleContextOptions() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, true).chat(MESSAGES);

            assertFalse(s.lastRequest().has("options"),
                    "FastFlowLM fixes context at startup and takes no per-request options");
        }
    }

    @Test
    void sendsTheDefaultMaxTokens() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false).chat(MESSAGES);

            assertEquals(1024, s.lastRequest().path("max_tokens").asInt());
        }
    }

    @Test
    void sendsTheConfiguredMaxTokens() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("ok"));
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false, 2048).chat(MESSAGES);

            assertEquals(2048, s.lastRequest().path("max_tokens").asInt());
            assertEquals(2048, new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false, 2048)
                    .maxTokens());
        }
    }

    @Test
    void requestsStreamingAndRelaysDeltas() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("Hel"), delta("lo"), "[DONE]");
            List<String> out = new ArrayList<>();
            new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false).chatStream(MESSAGES, out::add);

            assertTrue(s.lastRequest().path("stream").asBoolean());
            assertEquals(List.of("Hel", "lo"), out);
        }
    }

    @Test
    void readsTheNonStreamingReply() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(completion("  hi there  "));
            assertEquals("hi there", new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false)
                    .chat(MESSAGES));
        }
    }

    @Test
    void listsModelsFromTheOpenAiModelsEndpoint() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.modelsJson("{\"data\":[{\"id\":\"qwen2.5-it:3b\"},{\"id\":\"gemma3:1b\"}]}");
            FastFlowLmProvider provider = new FastFlowLmProvider("qwen2.5-it:3b", s.url(), 0.7, false);

            assertEquals(List.of("qwen2.5-it:3b", "gemma3:1b"), provider.listModels());
            assertEquals("/v1/models", s.lastPath());
        }
    }

    @Test
    void surfacesRuntimeErrorsWithTheProvidersName() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatFailure(404, "{\"error\":{\"message\":\"unknown model\"}}");
            FastFlowLmProvider provider = new FastFlowLmProvider("nope:1b", s.url(), 0.7, false);

            LlmException e = assertThrows(LlmException.class, () -> provider.chat(MESSAGES));

            assertTrue(e.getMessage().contains("unknown model"), e.getMessage());
        }
    }

    @Test
    void reportsAnUnreachableRuntime() {
        FastFlowLmProvider provider = new FastFlowLmProvider("qwen2.5-it:3b", "http://127.0.0.1:1", 0.7, false);

        LlmException e = assertThrows(LlmException.class, () -> provider.chat(MESSAGES));

        assertTrue(e.getMessage().contains("FastFlowLM"), e.getMessage());
    }

    @Test
    void advertisesNoPerRequestContextLength() {
        FastFlowLmProvider provider = new FastFlowLmProvider("qwen2.5-it:3b", "http://127.0.0.1:52625", 0.7, false);

        assertFalse(provider.capabilities().has(LlmCapability.PER_REQUEST_CONTEXT_LENGTH));
        assertTrue(provider.capabilities().has(LlmCapability.THINKING));
        assertEquals("fastflowlm", provider.providerName());
        assertEquals("http://127.0.0.1:52625", provider.baseUrl());
    }
}