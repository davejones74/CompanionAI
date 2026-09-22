package davejones74.campanionai.retrieval;

import davejones74.campanionai.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmIntentClassifierTest {

    @Test
    void classifiesViaLlm() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/api/chat", "{\"message\":{\"content\":"
                    + "\"{\\\"intent\\\":\\\"weather\\\",\\\"location\\\":\\\"London\\\",\\\"team\\\":\\\"\\\",\\\"past\\\":1}\"}}");
            LlmClient llm = new LlmClient("test-model", s.url(), 0.7);
            LlmIntentClassifier classifier = new LlmIntentClassifier(llm);

            Optional<IntentClassification> c = classifier.classify(
                    "what was yesterday's weather?", List.of(new LlmClient.ChatMessage("user", "hi")));

            assertTrue(c.isPresent());
            assertEquals(Intent.WEATHER, c.get().intent());
            assertEquals("London", c.get().location());
            assertEquals(1, c.get().pastDays());
            assertNull(c.get().team());
        }
    }

    @Test
    void nonJsonReplyFallsBackToNone() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/api/chat", "{\"message\":{\"content\":\"sorry, I cannot help\"}}");
            LlmClient llm = new LlmClient("test-model", s.url(), 0.7);
            LlmIntentClassifier classifier = new LlmIntentClassifier(llm);

            Optional<IntentClassification> c = classifier.classify("hello", List.of());
            assertTrue(c.isPresent());
            assertEquals(Intent.NONE, c.get().intent());
        }
    }

    @Test
    void llmFailureFallsBackToNone() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/api/chat", "{\"error\":\"model not found\"}");
            LlmClient llm = new LlmClient("missing-model", s.url(), 0.7);
            LlmIntentClassifier classifier = new LlmIntentClassifier(llm);

            Optional<IntentClassification> c = classifier.classify("hello", List.of());
            assertTrue(c.isPresent());
            assertEquals(Intent.NONE, c.get().intent());
        }
    }
}