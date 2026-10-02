package davejones74.campanionai.retrieval;

import davejones74.campanionai.llm.LlmMessage;
import davejones74.campanionai.llm.OllamaProvider;
import davejones74.campanionai.llm.StubLlmServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmIntentClassifierTest {

    private static String chatCompletion(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void classifiesViaLlm() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(chatCompletion(
                    "{\\\"intent\\\":\\\"weather\\\",\\\"location\\\":\\\"London\\\",\\\"team\\\":\\\"\\\",\\\"past\\\":1}"));
            LlmIntentClassifier classifier =
                    new LlmIntentClassifier(new OllamaProvider("test-model", s.url(), 0.7, null));

            Optional<IntentClassification> c = classifier.classify(
                    "what was yesterday's weather?", List.of(new LlmMessage("user", "hi")));

            assertTrue(c.isPresent());
            assertEquals(Intent.WEATHER, c.get().intent());
            assertEquals("London", c.get().location());
            assertEquals(1, c.get().pastDays());
            assertNull(c.get().team());
        }
    }

    @Test
    void nonJsonReplyFallsBackToNone() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(chatCompletion("sorry, I cannot help"));
            LlmIntentClassifier classifier =
                    new LlmIntentClassifier(new OllamaProvider("test-model", s.url(), 0.7, null));

            Optional<IntentClassification> c = classifier.classify("hello", List.of());
            assertTrue(c.isPresent());
            assertEquals(Intent.NONE, c.get().intent());
        }
    }

    @Test
    void llmFailureFallsBackToNone() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatFailure(404, "{\"error\":{\"message\":\"model not found\"}}");
            LlmIntentClassifier classifier =
                    new LlmIntentClassifier(new OllamaProvider("missing-model", s.url(), 0.7, null));

            Optional<IntentClassification> c = classifier.classify("hello", List.of());
            assertTrue(c.isPresent());
            assertEquals(Intent.NONE, c.get().intent());
        }
    }

    @Test
    void sendsOpenAiChatCompletionsRequest() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(chatCompletion("{\"intent\":\"none\"}"));
            LlmIntentClassifier classifier =
                    new LlmIntentClassifier(new OllamaProvider("test-model", s.url(), 0.7, null));

            classifier.classify("hello", List.of());

            assertEquals("/v1/chat/completions", s.lastPath());
            assertEquals("test-model", s.lastRequest().path("model").asText());
            assertEquals("system", s.lastRequest().path("messages").get(0).path("role").asText());
        }
    }
}