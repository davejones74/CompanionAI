package davejones74.campanionai.retrieval;

import davejones74.campanionai.LlmClient;
import davejones74.campanionai.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServiceTest {

    private static final List<LlmClient.ChatMessage> HISTORY = List.of();

    private static final class StubClassifier implements IntentClassifier {
        final Optional<IntentClassification> result;
        int calls;
        StubClassifier(IntentClassification result) {
            this.result = Optional.ofNullable(result);
        }
        @Override
        public Optional<IntentClassification> classify(String input, List<LlmClient.ChatMessage> history) {
            calls++;
            return result;
        }
    }

    private static final class StubProvider implements RetrievalProvider {
        final RetrievalKind kind;
        RetrievalResult result;
        RetrievalException toThrow;
        int calls;
        StubProvider(RetrievalKind kind) {
            this.kind = kind;
        }
        @Override
        public RetrievalKind kind() {
            return kind;
        }
        @Override
        public boolean isConfigured() {
            return true;
        }
        @Override
        public RetrievalResult retrieve(RetrievalRequest request) throws RetrievalException {
            calls++;
            if (toThrow != null) throw toThrow;
            return result;
        }
    }

    private static RetrievalService service(IntentClassifier rules, IntentClassifier llm,
                                            Map<RetrievalKind, RetrievalProvider> providers) {
        return new RetrievalService(rules, llm, providers, "Uxbridge, UK", 4000);
    }

    @Test
    void dispatchesWeatherAndEnrichesPrompt() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(),
                List.of(new RetrievalItem("Open-Meteo", "Weather for Uxbridge", "http://x", null, null,
                        "Current: 15C, partly cloudy.")));
        RetrievalService service = service(new StubClassifier(
                new IntentClassification(Intent.WEATHER, null, null, 0)), null,
                Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("weather?", HISTORY);

        assertEquals(1, weather.calls);
        assertTrue(ctx.attempted());
        assertFalse(ctx.failed());
        assertTrue(ctx.promptBlock().contains("Current external information"));
        assertTrue(ctx.promptBlock().contains("<retrieved-content>"));
        assertFalse(ctx.isBlank());
    }

    @Test
    void noneIntentSkipsProviders() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        RetrievalService service = service(new StubClassifier(new IntentClassification(Intent.NONE, null, null, 0)),
                null, Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("hello", HISTORY);

        assertEquals(0, weather.calls);
        assertFalse(ctx.attempted());
        assertTrue(ctx.isBlank());
    }

    @Test
    void knowledgeBaseIntentSkipsProviders() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        RetrievalService service = service(new StubClassifier(new IntentClassification(Intent.KNOWLEDGE_BASE, null, null, 0)),
                null, Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("summarise my upload", HISTORY);
        assertEquals(0, weather.calls);
        assertFalse(ctx.attempted());
        assertTrue(ctx.isBlank());
    }

    @Test
    void emptyClassificationSkipsProviders() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        RetrievalService service = service(new StubClassifier(null), null,
                Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("whatever", HISTORY);
        assertEquals(0, weather.calls);
        assertFalse(ctx.attempted());
        assertTrue(ctx.isBlank());
    }

    @Test
    void unconfiguredProviderIsSilentlySkipped() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        RetrievalService service = new RetrievalService(new StubClassifier(
                new IntentClassification(Intent.WEATHER, null, null, 0)),
                null, Map.of(), "", 4000);

        LiveContext ctx = service.supplement("weather?", HISTORY);
        assertFalse(ctx.attempted());
        assertTrue(ctx.isBlank());
    }

    @Test
    void providerFailureBecomesFriendlyNotice() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.toThrow = new RetrievalException("boom",
                "Current weather information could not be retrieved.", null);
        RetrievalService service = service(new StubClassifier(
                new IntentClassification(Intent.WEATHER, null, null, 0)),
                null, Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("weather?", HISTORY);

        assertTrue(ctx.attempted());
        assertTrue(ctx.failed());
        assertTrue(ctx.promptBlock().contains("[Note:"), ctx.promptBlock());
        assertTrue(ctx.promptBlock().contains("could not be retrieved"), ctx.promptBlock());
    }

    @Test
    void emptyResultsStillNotice() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        RetrievalService service = service(new StubClassifier(
                new IntentClassification(Intent.WEATHER, null, null, 0)),
                null, Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("weather?", HISTORY);
        assertTrue(ctx.attempted());
        assertFalse(ctx.failed());
        assertTrue(ctx.promptBlock().contains("no results"), ctx.promptBlock());
    }

    @Test
    void usesLlmClassifierWhenRulesReturnEmpty() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(),
                List.of(new RetrievalItem("Open-Meteo", "Weather", "http://x", null, null, "Sunny.")));
        StubClassifier rulesNone = new StubClassifier(null);
        StubClassifier llmWeather = new StubClassifier(new IntentClassification(Intent.WEATHER, "Paris", null, 0));
        RetrievalService service = service(rulesNone, llmWeather, Map.of(RetrievalKind.WEATHER, weather));

        LiveContext ctx = service.supplement("formal weather query", HISTORY);

        assertEquals(1, weather.calls);
        assertEquals(1, rulesNone.calls);
        assertTrue(ctx.attempted());
        String prompt = ctx.promptBlock();
        assertTrue(prompt.contains("Current external information"), prompt);
    }

    @Test
    void respectsContextTokenBudget() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(),
                List.of(
                        new RetrievalItem("Open-Meteo", "Weather", "http://x", null, null, longText(400)),
                        new RetrievalItem("Open-Meteo", "Weather 2", "http://y", null, null, longText(400))));
        RetrievalService service = new RetrievalService(new StubClassifier(
                new IntentClassification(Intent.WEATHER, null, null, 0)),
                null, Map.of(RetrievalKind.WEATHER, weather), "", 200);

        LiveContext ctx = service.supplement("weather?", HISTORY);

        assertTrue(ctx.attempted());
        assertTrue(ctx.promptBlock().contains("[1]"), ctx.promptBlock());
        assertFalse(ctx.promptBlock().contains("[2]"), ctx.promptBlock());
        assertTrue(Tokens.estimate(ctx.promptBlock()) <= 200 + 64, "budget overshot: " + Tokens.estimate(ctx.promptBlock()));
    }

    private static String longText(int chars) {
        return "x".repeat(chars);
    }
}