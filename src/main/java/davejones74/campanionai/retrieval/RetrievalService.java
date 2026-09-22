package davejones74.campanionai.retrieval;

import davejones74.campanionai.LlmClient;
import davejones74.campanionai.Tokens;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates classification and provider dispatch for external information.
 * Never throws to its caller: a provider failure becomes a short, friendly
 * notice inside the prompt so the conversation continues normally.
 */
public final class RetrievalService {

    private final IntentClassifier rules;
    private final IntentClassifier llmClassifier;
    private final Map<RetrievalKind, RetrievalProvider> providers;
    private final String defaultLocation;
    private final int contextTokens;

    public RetrievalService(IntentClassifier rules,
                            IntentClassifier llmClassifier,
                            Map<RetrievalKind, RetrievalProvider> providers,
                            String defaultLocation,
                            int contextTokens) {
        this.rules = rules == null ? new RuleIntentClassifier() : rules;
        this.llmClassifier = llmClassifier;
        this.providers = new EnumMap<>(RetrievalKind.class);
        if (providers != null) {
            this.providers.putAll(providers);
        }
        this.defaultLocation = defaultLocation == null || defaultLocation.isBlank() ? null : defaultLocation.trim();
        this.contextTokens = Math.max(256, contextTokens);
    }

    public LiveContext supplement(String input, List<LlmClient.ChatMessage> history) {
        Optional<IntentClassification> classification = rules.classify(input, history);
        if (classification.isEmpty() && llmClassifier != null) {
            classification = llmClassifier.classify(input, history);
        }
        if (classification.isEmpty()) {
            return new LiveContext("", false, false);
        }
        IntentClassification cls = classification.get();
        RetrievalKind kind = toKind(cls.intent());
        if (kind == null) {
            return new LiveContext("", false, false);
        }
        RetrievalProvider provider = providers.get(kind);
        if (provider == null || !provider.isConfigured()) {
            return new LiveContext("", false, false);
        }
        String location = cls.location() != null ? cls.location() : defaultLocation;
        Freshness freshness = switch (kind) {
            case WEATHER -> cls.pastDays() > 0 ? Freshness.ANY : Freshness.TODAY;
            case SPORTS -> Freshness.TODAY;
            case WEB_SEARCH -> Freshness.RECENT;
        };
        RetrievalRequest request = new RetrievalRequest(input, kind, location, freshness, cls.pastDays(), cls.team());
        try {
            RetrievalResult result = provider.retrieve(request);
            if (result.items().isEmpty()) {
                return new LiveContext(notice("The search returned no results."), true, false);
            }
            return new LiveContext(format(result), true, false);
        } catch (RetrievalException e) {
            LOG.warn("Live retrieval failed ({}) : {}", kind, String.valueOf(e.getMessage()));
            return new LiveContext(notice(e.userFacingMessage()), true, true);
        }
    }

    private static RetrievalKind toKind(Intent intent) {
        if (intent == null) return null;
        return switch (intent) {
            case WEB_SEARCH -> RetrievalKind.WEB_SEARCH;
            case WEATHER -> RetrievalKind.WEATHER;
            case SPORTS -> RetrievalKind.SPORTS;
            case NONE, KNOWLEDGE_BASE -> null;
        };
    }

    private String format(RetrievalResult result) {
        StringBuilder sb = new StringBuilder()
                .append("Current external information (").append(label(result.kind()))
                .append(", retrieved ").append(result.retrievedAt()).append("):\n");
        int used = Tokens.estimate(sb.toString());
        int n = 0;
        for (RetrievalItem item : result.items()) {
            n++;
            String body = renderItem(n, item);
            String block = "<retrieved-content>\n" + body + "</retrieved-content>\n";
            int tokens = Tokens.estimate(block);
            if (used + tokens > contextTokens) break;
            sb.append(block).append('\n');
            used += tokens;
        }
        return sb.toString().trim();
    }

    private static String renderItem(int index, RetrievalItem item) {
        StringBuilder sb = new StringBuilder("[").append(index).append("]\n");
        if (!item.title().isBlank()) sb.append("Title: ").append(item.title()).append('\n');
        if (!item.source().isBlank()) sb.append("Source: ").append(item.source()).append('\n');
        if (item.published() != null && !item.published().isBlank()) {
            sb.append("Published: ").append(item.published()).append('\n');
        }
        if (item.url() != null) sb.append("URL: ").append(item.url()).append('\n');
        String content = item.content() != null && !item.content().isBlank() ? item.content() : item.snippet();
        if (content != null && !content.isBlank()) {
            sb.append("Content:\n").append(content.trim());
        }
        return sb.toString();
    }

    private static String label(RetrievalKind kind) {
        return switch (kind) {
            case WEB_SEARCH -> "web";
            case WEATHER -> "weather";
            case SPORTS -> "sport";
        };
    }

    private static String notice(String message) {
        return "[Note: " + message + "]";
    }

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(RetrievalService.class);
}