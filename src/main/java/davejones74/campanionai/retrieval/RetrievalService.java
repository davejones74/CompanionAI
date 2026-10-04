package davejones74.campanionai.retrieval;

import davejones74.campanionai.Source;
import davejones74.campanionai.Tokens;
import davejones74.campanionai.llm.LlmMessage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Orchestrates classification and provider dispatch for external information.
 * Never throws to its caller: a provider failure becomes a short, friendly
 * notice inside the prompt so the conversation continues normally.
 *
 * <p>A hostname named by the user becomes a scope applied to every lookup, and the resulting
 * prompt states that scope, the searches run, and how much evidence was obtained. Without those
 * facts in the prompt the model has no basis for distinguishing "the searches returned nothing"
 * from "there is nothing on this website", and reports the second when only the first is true.
 */
public final class RetrievalService {

    private static final Logger LOG = LogManager.getLogger(RetrievalService.class);

    /**
     * How the model must reason about a bounded result set.
     *
     * <p>The wording matters as much as the retrieval. Retrieval returning three pages is fine;
     * the model then saying "these are all the stories" is not, because nothing in the evidence
     * establishes that. Saying what was searched and what came back is always defensible.
     */
    private static final String GROUNDING_RULES = """
            Evidence rules:
            - The pages above are a bounded sample from the searches listed. They are not a survey of the whole site.
            - Never describe the set as complete. Do not say "all", "every", "the full list" or "all of the stories": \
            nothing here establishes that no other pages exist.
            - If no matching results came back, say only that these searches returned nothing. Never claim the \
            website has no such pages; you have seen a few searches, not the site.
            - Answer only from the retrieved content. If it does not cover the question, say what is missing \
            instead of filling the gap.
            - When reporting results, give the scope and the count, for example: "I found 18 matching pages \
            across the 3 searches I ran.".""";

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
        return supplement(input, history, RetrievalProgress.NOOP);
    }

    public LiveContext supplement(String input, List<LlmMessage> history, RetrievalProgress progress) {
        RetrievalProgress listener = progress == null ? RetrievalProgress.NOOP : progress;
        Optional<IntentClassification> classification = rules.classify(input, history);
        if (classification.isEmpty() && llmClassifier != null) {
            classification = llmClassifier.classify(input, history);
        }
        if (classification.isEmpty()) {
            return LiveContext.empty();
        }
        IntentClassification cls = classification.get();
        RetrievalKind kind = toKind(cls.intent());
        if (kind == null) {
            return LiveContext.empty();
        }
        RetrievalProvider provider = providers.get(kind);
        if (provider == null || !provider.isConfigured()) {
            return LiveContext.empty();
        }

        // A named site is a property of the request, not of the classifier, so it is resolved
        // here and therefore applies whichever classifier decided the intent.
        WebScope scope = WebScope.extract(input).orElse(null);
        String host = scope == null ? null : scope.host();
        String lookupText = scope == null ? input : scope.remainder();
        WebSearchProfile profile = kind == RetrievalKind.WEB_SEARCH
                ? WebSearchProfile.detect(input) : null;

        String location = cls.location() != null ? cls.location() : defaultLocation;
        Freshness freshness = switch (kind) {
            case WEATHER -> cls.pastDays() > 0 ? Freshness.ANY : Freshness.TODAY;
            case SPORTS -> Freshness.TODAY;
            // Research must not inherit a recency window: a sweep for "everything about X" would
            // otherwise silently drop older material. The profile carries the time behaviour.
            case WEB_SEARCH -> profile == WebSearchProfile.RESEARCH ? Freshness.ANY : Freshness.RECENT;
        };
        RetrievalRequest request = new RetrievalRequest(lookupText, kind, location, freshness,
                cls.pastDays(), cls.team(), host, profile);

        if (kind == RetrievalKind.WEB_SEARCH) {
            listener.onProgress(host == null ? "Searching the web..." : "Searching " + host + "...");
        }
        try {
            RetrievalResult result = provider.retrieve(request);
            if (kind == RetrievalKind.WEB_SEARCH) {
                reportProgress(listener, result, profile);
                return new LiveContext(formatWeb(result, input), true, false, toSources(result), profile);
            }
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
                RetrievalRequest retry = new RetrievalRequest(lookupText, fallbackKind, location,
                        Freshness.RECENT, cls.pastDays(), cls.team(), host, null);
                try {
                    RetrievalResult result = fallback.retrieve(retry);
                    if (!result.items().isEmpty()) {
                        // The retry is a single lookup by construction, so it carries the
                        // lookup profile rather than the profile of the request that failed.
                        return new LiveContext(formatWeb(result, input), true, false, toSources(result),
                                WebSearchProfile.LOOKUP);
                    }
                } catch (RetrievalException retryError) {
                    LOG.warn("Fallback {} failed : {}", fallbackKind, String.valueOf(retryError.getMessage()));
                }
            }
            // The profile travels with the context even when the lookup failed, so that a
            // research request is still recognisable as one by whoever handles the failure.
            return new LiveContext(notice(e.userFacingMessage()), true, true, List.of(), profile);
        }
    }

    private static void reportProgress(RetrievalProgress listener, RetrievalResult result,
                                      WebSearchProfile profile) {
        int found = result.resultsReturned();
        if (found == 0) {
            listener.onProgress("No matching results returned.");
            return;
        }
        if (profile == WebSearchProfile.RESEARCH) {
            listener.onProgress("Found " + found + plural(found, " matching page", " matching pages")
                    + " across " + result.queries().size()
                    + plural(result.queries().size(), " search", " searches")
                    + ", " + result.pagesFetched() + " fetched.");
        } else {
            listener.onProgress("Found " + found + " result" + (found == 1 ? "" : "s") + ".");
        }
    }

    private static String plural(int count, String singular, String many) {
        return count == 1 ? singular : many;
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

    /**
     * Builds the web block: what was asked, where, how it was searched, and how much came back,
     * followed by the evidence itself and the rules for reasoning about it.
     *
     * <p>The zero-result case is deliberately a full block rather than a bare note. "No results"
     * is the exact situation where the model is most likely to over-claim, so it is given the
     * searches that were run and told what that does not establish.
     */
    private String formatWeb(RetrievalResult result, String userRequest) {
        StringBuilder sb = new StringBuilder("WEB RESEARCH\n");
        sb.append("Scope: ").append(result.domain() == null
                ? "the whole web (no site was named)"
                : result.domain() + " (results restricted to this host and its subdomains)").append('\n');
        if (userRequest != null && !userRequest.isBlank()) {
            sb.append("User request: ").append(userRequest.trim()).append('\n');
        }
        sb.append("Searches performed: ").append(result.queries().size()).append('\n');
        int q = 0;
        for (String query : result.queries()) {
            sb.append("  ").append(++q).append(". ").append(query).append('\n');
        }
        sb.append("Results returned: ").append(result.resultsReturned()).append('\n');
        sb.append("Pages retrieved in full: ").append(result.pagesFetched()).append('\n');
        sb.append("Retrieved at: ").append(result.retrievedAt()).append('\n');

        if (result.resultsReturned() == 0) {
            sb.append("\nThese searches returned no results")
              .append(result.domain() == null ? "." : " for the requested scope.")
              .append(" That is an absence of matching results in this sample; it is not evidence that the")
              .append(" site has no such pages, and it is not evidence of their absence either.\n");
        } else {
            sb.append('\n');
        }

        String rules = "\n" + GROUNDING_RULES;
        int budget = contextTokens - Tokens.estimate(rules);
        int used = Tokens.estimate(sb.toString());
        int itemIndex = 0;
        for (RetrievalItem item : result.items()) {
            String block = "<retrieved-content>\n" + renderItem(++itemIndex, item)
                    + "</retrieved-content>\n\n";
            int tokens = Tokens.estimate(block);
            if (used + tokens > budget) break;
            sb.append(block);
            used += tokens;
        }
        return sb.append(rules).toString().trim();
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