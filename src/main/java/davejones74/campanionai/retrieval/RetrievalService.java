package davejones74.campanionai.retrieval;

import davejones74.campanionai.Tokens;
import davejones74.campanionai.Source;
import davejones74.campanionai.llm.LlmMessage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import davejones74.campanionai.Source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Orchestrates classification and provider dispatch for external information.
 * Never throws to its caller: a provider failure becomes a short, friendly
 * notice inside the prompt so the conversation continues normally.
 */
public final class RetrievalService {

    private static final Logger LOG = LogManager.getLogger(RetrievalService.class);

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

    public LiveContext supplement(String input, List<LlmMessage> history) {
        Optional<IntentClassification> classification = rules.classify(input, history);
        if (classification.isEmpty() && llmClassifier != null) {
            classification = llmClassifier.classify(input, history);
        }
        if (classification.isEmpty()) {
            return new LiveContext("", false, false, List.of());
        }
        IntentClassification cls = classification.get();
        RetrievalKind kind = toKind(cls.intent());
        if (kind == null) {
            return new LiveContext("", false, false, List.of());
        }
        RetrievalProvider provider = providers.get(kind);
        if (provider == null || !provider.isConfigured()) {
            return new LiveContext("", false, false, List.of());
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
                return new LiveContext(notice("The search returned no results."), true, false, List.of());
            }
            return new LiveContext(format(result), true, false, toSources(result));
        } catch (RetrievalException e) {
            LOG.warn("Live retrieval failed ({}) : {}", kind, String.valueOf(e.getMessage()));
            // A sports provider on a plan that does not cover the current season
            // fails every single time, which makes live sport permanently
            // unanswerable through it. Web search covers the same ground well enough
            // to be worth trying before giving up.
            RetrievalKind fallbackKind = fallbackFor(kind);
            RetrievalProvider fallback = fallbackKind == null ? null : providers.get(fallbackKind);
            if (fallback != null && fallback.isConfigured()) {
                LOG.info("Retrying {} as {}.", kind, fallbackKind);
                RetrievalRequest retry = new RetrievalRequest(input, fallbackKind, location,
                        Freshness.RECENT, cls.pastDays(), cls.team());
                try {
                    RetrievalResult result = fallback.retrieve(retry);
                    if (!result.items().isEmpty()) {
                        return new LiveContext(format(result), true, false, toSources(result));
                    }
                } catch (RetrievalException retryError) {
                    LOG.warn("Fallback {} failed : {}", fallbackKind, String.valueOf(retryError.getMessage()));
                }
            }
            return new LiveContext(notice(e.userFacingMessage()), true, true, List.of());
        }
    }

    /**
     * Where to retry a failed lookup. Only SPORTS falls back: web search has no
     * stricter source to degrade to, and weather degrades to itself.
     */
    private static RetrievalKind fallbackFor(RetrievalKind kind) {
        return kind == RetrievalKind.SPORTS ? RetrievalKind.WEB_SEARCH : null;
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



    private List<Source> toSources(RetrievalResult result) {
        List<Source> sources = new ArrayList<>();
        if (result == null || result.items() == null) return sources;
        for (RetrievalItem item : result.items()) {
            if (item.url() != null && !item.url().isBlank()) {
                sources.add(new Source(item.title(), item.url()));
            }
        }
        return sources;
    }
}