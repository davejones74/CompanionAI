package davejones74.campanionai.retrieval;

import java.util.Locale;
import java.util.Set;

/**
 * The bounded budget for one web profile: how many searches, how many results, how many pages to
 * fetch, and the search parameters for those calls.
 *
 * <p>Both the profile budgets and the engine's own limits are enforced here, because a value
 * that the engine would reject would otherwise be discovered as a failed request rather than as
 * a configuration mistake.
 */
public record WebSearchProfileSettings(
        int maxResults,
        int fetchPages,
        int maxQueries,
        String searchDepth,
        String topic,
        String timeRange) {

    /** Tavily's documented ceiling for {@code max_results}. */
    public static final int RESULT_CEILING = 20;

    private static final Set<String> DEPTHS = Set.of("basic", "fast", "advanced", "ultra-fast");
    private static final Set<String> TOPICS = Set.of("general", "news", "finance");
    private static final Set<String> TIME_RANGES = Set.of("day", "week", "month", "year");

    public WebSearchProfileSettings {
        maxResults = Math.max(1, Math.min(RESULT_CEILING, maxResults));
        fetchPages = Math.max(0, Math.min(RESULT_CEILING, fetchPages));
        maxQueries = Math.max(1, Math.min(WebSearchProfile.MAX_RESEARCH_QUERIES, maxQueries));
        searchDepth = normalise(searchDepth, DEPTHS, "basic");
        topic = normalise(topic, TOPICS, "general");
        timeRange = timeRange == null || timeRange.isBlank()
                ? null
                : normalise(timeRange, TIME_RANGES, null);
    }

    /** A single cheap search with no date restriction. */
    public static WebSearchProfileSettings lookup() {
        return new WebSearchProfileSettings(5, 2, 1, "basic", "news", "week");
    }

    /**
     * A bounded sweep across all time, fetching real pages for evidence.
     *
     * <p>No time range: a sweep for "everything about X" must not silently exclude older
     * material, and the model is told the result is a sample rather than a complete list.
     */
    public static WebSearchProfileSettings research() {
        return new WebSearchProfileSettings(RESULT_CEILING, 6,
                WebSearchProfile.MAX_RESEARCH_QUERIES, "advanced", "general", null);
    }

    /**
     * @return one of {@code allowed}, {@code null} when blank (meaning "do not send"), or
     *         {@code fallback} when the value is not something the engine accepts
     */
    private static String normalise(String value, Set<String> allowed, String fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return fallback;
        }
        return allowed.contains(v) ? v : fallback;
    }
}