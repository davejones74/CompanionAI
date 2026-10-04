package davejones74.campanionai.retrieval;

import davejones74.campanionai.Tokens;
import davejones74.campanionai.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServiceTest {

    private static final List<LlmMessage> HISTORY = List.of();

    private static final class StubClassifier implements IntentClassifier {
        final Optional<IntentClassification> result;
        int calls;
        StubClassifier(IntentClassification result) {
            this.result = Optional.ofNullable(result);
        }
        @Override
        public Optional<IntentClassification> classify(String input, List<LlmMessage> history) {
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

    // ---- scope, research profile, structured context -------------------------------

    private static final class RecordingProvider implements RetrievalProvider {
        final RetrievalKind kind;
        RetrievalResult result;
        RetrievalRequest lastRequest;
        int calls;

        RecordingProvider(RetrievalKind kind) {
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
        public RetrievalResult retrieve(RetrievalRequest request) {
            calls++;
            lastRequest = request;
            return result;
        }
    }

    private static RecordingProvider web(RetrievalResult result) {
        RecordingProvider p = new RecordingProvider(RetrievalKind.WEB_SEARCH);
        p.result = result;
        return p;
    }

    private static RetrievalService webService(RecordingProvider provider) {
        return new RetrievalService(new RuleIntentClassifier(), null,
                Map.of(RetrievalKind.WEB_SEARCH, provider), "", 4000);
    }

    private static RetrievalItem item(String url) {
        return new RetrievalItem("bbc.co.uk", "A story", url, "2026-09-22", "snippet text", null);
    }

    @Test
    void researchRequestCarriesScopeAndProfileAndStripsTheHostFromTheQuery() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), "bbc.co.uk", List.of("q"), 0));

        webService(provider).supplement("bbc.co.uk - give me all the stories about Huw Edwards", HISTORY);

        assertEquals(1, provider.calls);
        assertEquals("bbc.co.uk", provider.lastRequest.domain());
        assertEquals(WebSearchProfile.RESEARCH, provider.lastRequest.profile());
        assertEquals("give me all the stories about Huw Edwards", provider.lastRequest.query(),
                "the hostname belongs in the scope, not in the query text");
        assertEquals(Freshness.ANY, provider.lastRequest.freshness());
    }

    @Test
    void researchRequestIsNotScopedWhenNoHostWasNamed() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), null, List.of("q"), 0));

        webService(provider).supplement("give me all the stories about Huw Edwards", HISTORY);

        assertEquals(null, provider.lastRequest.domain());
        assertEquals(WebSearchProfile.RESEARCH, provider.lastRequest.profile());
    }

    @Test
    void aPlainNewsQuestionStaysALookup() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), null, List.of("q"), 0));

        webService(provider).supplement("what is the latest news on AI models?", HISTORY);

        assertEquals(WebSearchProfile.LOOKUP, provider.lastRequest.profile());
        assertEquals(Freshness.RECENT, provider.lastRequest.freshness());
    }

    @Test
    void structuredBlockStatesScopeQueriesAndCounts() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1"), item("https://www.bbc.co.uk/news/2")),
                "bbc.co.uk",
                List.of("give me all the stories about Huw Edwards", "Huw Edwards",
                        "Huw Edwards site:bbc.co.uk"),
                2));

        LiveContext ctx = webService(provider).supplement(
                "bbc.co.uk - give me all the stories about Huw Edwards", HISTORY);

        String prompt = ctx.promptBlock();
        assertTrue(prompt.startsWith("WEB RESEARCH"), prompt);
        assertTrue(prompt.contains("Scope: bbc.co.uk"), prompt);
        assertTrue(prompt.contains("User request: bbc.co.uk - give me all the stories about Huw Edwards"),
                prompt);
        assertTrue(prompt.contains("Searches performed: 3"), prompt);
        assertTrue(prompt.contains("  1. give me all the stories about Huw Edwards"), prompt);
        assertTrue(prompt.contains("  3. Huw Edwards site:bbc.co.uk"), prompt);
        assertTrue(prompt.contains("Results returned: 2"), prompt);
        assertTrue(prompt.contains("Pages retrieved in full: 2"), prompt);
        assertTrue(prompt.contains("<retrieved-content>"), prompt);
        assertEquals(2, ctx.sources().size());
    }

    @Test
    void groundingRulesForbidExhaustiveClaimsAndProofOfAbsence() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), "bbc.co.uk", List.of("q"), 0));

        String prompt = webService(provider).supplement(
                "bbc.co.uk - give me all the stories about Huw Edwards", HISTORY).promptBlock();

        assertTrue(prompt.contains("not a survey of the whole site"), prompt);
        assertTrue(prompt.contains("Never describe the set as complete"), prompt);
        assertTrue(prompt.contains("Never claim the website has no such pages"), prompt);
        assertTrue(prompt.contains("I found 18 matching pages across the 3 searches I ran"), prompt);
    }

    @Test
    void noResultsProducesAnExplicitAbsenceOfEvidenceRatherThanProofOfAbsence() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(), "bbc.co.uk", List.of("q1", "q2"), 0));

        LiveContext ctx = webService(provider).supplement("bbc.co.uk - stories about nobody", HISTORY);

        String prompt = ctx.promptBlock();
        assertTrue(prompt.contains("Results returned: 0"), prompt);
        assertTrue(prompt.contains("these searches returned no results")
                || prompt.contains("These searches returned no results"), prompt);
        assertTrue(prompt.contains("not evidence that the site has no such pages"), prompt);
        assertTrue(prompt.contains("Evidence rules:"),
                "the rules are present even when there is no evidence to apply them to");
        assertTrue(ctx.sources().isEmpty());
    }

    @Test
    void unscopedResearchSaysTheScopeIsTheWholeWeb() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), null, List.of("q"), 0));

        String prompt = webService(provider).supplement("all the stories about Huw Edwards", HISTORY)
                .promptBlock();

        assertTrue(prompt.contains("Scope: the whole web (no site was named)"), prompt);
    }

    @Test
    void progressReportsScopeThenCounts() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1"), item("https://www.bbc.co.uk/news/2")),
                "bbc.co.uk", List.of("q1", "q2", "q3"), 2));
        List<String> events = new ArrayList<>();

        webService(provider).supplement("bbc.co.uk - all the stories about Huw Edwards", HISTORY,
                events::add);

        assertEquals(2, events.size(), events.toString());
        assertEquals("Searching bbc.co.uk...", events.get(0));
        assertEquals("Found 2 matching pages across 3 searches, 2 fetched.", events.get(1));
    }

    @Test
    void progressForAPlainLookupDoesNotClaimASweep() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(item("https://www.bbc.co.uk/news/1")), null, List.of("q"), 0));
        List<String> events = new ArrayList<>();

        webService(provider).supplement("what is the latest news on AI models?", HISTORY, events::add);

        assertEquals("Searching the web...", events.get(0));
        assertEquals("Found 1 result.", events.get(1));
    }

    @Test
    void progressSaysWhenNothingCameBack() {
        RecordingProvider provider = web(new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                List.of(), "bbc.co.uk", List.of("q"), 0));
        List<String> events = new ArrayList<>();

        webService(provider).supplement("bbc.co.uk - stories about nobody", HISTORY, events::add);

        assertEquals("Searching bbc.co.uk...", events.get(0));
        assertEquals("No matching results returned.", events.get(1));
    }

    @Test
    void noProgressIsReportedForOtherKinds() {
        StubProvider weather = new StubProvider(RetrievalKind.WEATHER);
        weather.result = new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of());
        List<String> events = new ArrayList<>();

        service(new StubClassifier(new IntentClassification(Intent.WEATHER, null, null, 0)), null,
                Map.of(RetrievalKind.WEATHER, weather)).supplement("weather?", HISTORY, events::add);

        assertTrue(events.isEmpty(), events.toString());
    }

    private static String longText(int chars) {
        return "x".repeat(chars);
    }
}
