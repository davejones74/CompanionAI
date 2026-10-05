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

class OpenAiCompatTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT_PATH = "/v1/chat/completions";

    private static ObjectNode request(boolean stream) {
        ObjectNode node = OpenAiChat.envelope(MAPPER, "test-model",
                List.of(new LlmMessage("system", "be terse"), new LlmMessage("user", "hi")), 0.7, stream);
        return node;
    }

    private static String replyWith(String content) {
        JsonNode message = MAPPER.createObjectNode().put("content", content);
        JsonNode choice = MAPPER.createObjectNode().set("message", message);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("choices", MAPPER.createArrayNode().add(choice));
        return root.toString();
    }

    private static String delta(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void sendsOpenAiChatRequest() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(replyWith("ok"));
            new OpenAiCompatTransport(s.url(), "Test").complete(CHAT_PATH, request(false).toString());

            JsonNode sent = s.lastRequest();
            assertEquals("test-model", sent.path("model").asText());
            assertFalse(sent.path("stream").asBoolean(true));
            assertEquals(0.7, sent.path("temperature").asDouble(), 1e-9);
            assertEquals("system", sent.path("messages").get(0).path("role").asText());
            assertEquals("be terse", sent.path("messages").get(0).path("content").asText());
            assertEquals("user", sent.path("messages").get(1).path("role").asText());
        }
    }

    @Test
    void readsAndTrimsNonStreamingReply() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(replyWith("  hello  "));
            assertEquals("hello", new OpenAiCompatTransport(s.url(), "Test")
                    .complete(CHAT_PATH, request(false).toString()));
        }
    }

    @Test
    void mapsStringErrorBody() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson("{\"error\":\"model not found\"}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .complete(CHAT_PATH, request(false).toString()));
            assertTrue(e.getMessage().contains("model not found"), e.getMessage());
        }
    }

    @Test
    void mapsObjectErrorBody() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson("{\"error\":{\"message\":\"unknown model\",\"type\":\"invalid_request_error\"}}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .complete(CHAT_PATH, request(false).toString()));
            assertTrue(e.getMessage().contains("unknown model"), e.getMessage());
        }
    }

    @Test
    void mapsNon200Status() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatFailure(404, "{\"detail\":\"nope\"}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .complete(CHAT_PATH, request(false).toString()));
            assertTrue(e.getMessage().contains("404"), e.getMessage());
        }
    }

    @Test
    void rejectsReplyWithoutContent() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson("{\"choices\":[]}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .complete(CHAT_PATH, request(false).toString()));
            assertTrue(e.getMessage().contains("no message content"), e.getMessage());
        }
    }

    @Test
    void readsSseDeltasUntilDone() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
                    delta("Hel"), delta("lo"), "[DONE]", delta("ignored"));
            List<String> out = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test")
                    .stream(CHAT_PATH, request(true).toString(), out::add);
            assertEquals(List.of("Hel", "lo"), out);
            assertTrue(s.lastRequest().path("stream").asBoolean());
        }
    }

    @Test
    void readsSseThatEndsWithoutDoneTerminator() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("a"), delta("b"));
            List<String> out = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test")
                    .stream(CHAT_PATH, request(true).toString(), out::add);
            assertEquals(List.of("a", "b"), out);
        }
    }

    @Test
    void readsBareJsonLinesWithoutSseFraming() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatLines(delta("x"), delta("y"));
            List<String> out = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test")
                    .stream(CHAT_PATH, request(true).toString(), out::add);
            assertEquals(List.of("x", "y"), out);
        }
    }

    @Test
    void fallsBackToMessageContentInStream() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse("{\"choices\":[{\"message\":{\"content\":\"whole\"}}]}");
            List<String> out = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test")
                    .stream(CHAT_PATH, request(true).toString(), out::add);
            assertEquals(List.of("whole"), out);
        }
    }

    @Test
    void reportsFinishReasonAndUsageOnCompletion() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("Hel"),
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],"
                            + "\"usage\":{\"completion_tokens\":1024}}",
                    "[DONE]");
            List<String> out = new ArrayList<>();
            List<StreamCompletion> completions = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test").stream(CHAT_PATH, request(true).toString(),
                    new LlmProvider.ChunkHandler() {
                        @Override
                        public void onDelta(String delta) {
                            out.add(delta);
                        }

                        @Override
                        public void onComplete(StreamCompletion completion) {
                            completions.add(completion);
                        }
                    });

            assertEquals(List.of("Hel"), out);
            assertEquals(1, completions.size());
            assertEquals("length", completions.get(0).finishReason());
            assertEquals(1024, completions.get(0).completionTokens());
            assertTrue(completions.get(0).reachedOutputLimit());
        }
    }

    @Test
    void reportsCompletionWhenTheStreamEndsWithoutADoneTerminator() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("a"), delta("b"));
            List<StreamCompletion> completions = new ArrayList<>();
            new OpenAiCompatTransport(s.url(), "Test").stream(CHAT_PATH, request(true).toString(),
                    new LlmProvider.ChunkHandler() {
                        @Override
                        public void onDelta(String delta) {
                        }

                        @Override
                        public void onComplete(StreamCompletion completion) {
                            completions.add(completion);
                        }
                    });

            assertEquals(1, completions.size());
            assertEquals(null, completions.get(0).finishReason());
        }
    }

    @Test
    void doesNotReportCompletionWhenTheStreamFails() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("a"), "{\"error\":{\"message\":\"boom\"}}");
            List<StreamCompletion> completions = new ArrayList<>();
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .stream(CHAT_PATH, request(true).toString(), new LlmProvider.ChunkHandler() {
                                @Override
                                public void onDelta(String delta) {
                                }

                                @Override
                                public void onComplete(StreamCompletion completion) {
                                    completions.add(completion);
                                }
                            }));
            assertTrue(e.getMessage().contains("boom"), e.getMessage());
            assertTrue(completions.isEmpty());
        }
    }

    @Test
    void propagatesHandlerRuntimeExceptionsUnwrapped() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("a"), delta("b"), "[DONE]");
            IllegalStateException boom = new IllegalStateException("client gone");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .stream(CHAT_PATH, request(true).toString(), d -> {
                                throw boom;
                            }));
            assertEquals(boom, thrown);
        }
    }

    @Test
    void reportsErrorRaisedMidStream() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatSse(delta("a"), "{\"error\":{\"message\":\"context length exceeded\"}}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatTransport(s.url(), "Test")
                            .stream(CHAT_PATH, request(true).toString(), d -> { }));
            assertTrue(e.getMessage().contains("context length exceeded"), e.getMessage());
        }
    }

    @Test
    void reportsUnreachableRuntime() {
        LlmException e = assertThrows(LlmException.class,
                () -> new OpenAiCompatTransport("http://127.0.0.1:1", "Test")
                        .complete(CHAT_PATH, request(false).toString()));
        assertTrue(e.getMessage().contains("Failed to reach Test"), e.getMessage());
    }
}