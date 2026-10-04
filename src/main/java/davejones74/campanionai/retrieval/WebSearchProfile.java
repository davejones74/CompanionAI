package davejones74.campanionai.retrieval;

import java.util.Locale;
import java.util.Set;

/**
 * How much web work one user request is allowed to do.
 *
 * <p>The two profiles exist so that asking a broad question can cost more than asking a
 * pointed one without any single request being able to spend unbounded credit. {@link #LOOKUP}
 * is a single cheap search; {@link #RESEARCH} is a bounded sweep of several queries.
 *
 * <p>The profile is chosen deterministically from the words the user typed. It is never chosen
 * by the model, and choosing {@link #RESEARCH} never widens a requested site scope: research mode
 * spends more searches inside the scope, it does not search more of the internet.
 */
public enum WebSearchProfile {

    /** One search, the cheap depth, a short result list. */
    LOOKUP,

    /** Several complementary searches, the deeper depth, and page fetches for real evidence. */
    RESEARCH;

    /**
     * Hard ceiling on searches per request. Fixed rather than configurable to any larger value:
     * the whole point of the profile is a bound the application itself enforces, so the bound
     * lives in code where it cannot be widened by a deployment mistake.
     */
    public static final int MAX_RESEARCH_QUERIES = 3;

    /**
     * Words that mean "sweep the scope", as opposed to "answer one question".
     *
     * <p>Matched on word boundaries. That matters: a substring test for "all" also fires on
     * "football", "finally", "small" and "shall", which would turn a weather question into
     * three advanced searches.
     */
    private static final Set<String> RESEARCH_MARKERS = Set.of(
            "all", "every", "everything", "each", "entire", "entirely", "exhaustive",
            "comprehensive", "complete", "full", "coverage", "roundup", "stories", "articles",
            "pieces", "posts", "videos");

    /**
     * Picks the profile for an input that has already been classified as a web search.
     *
     * <p>Only research wording is considered. A bare domain such as {@code bbc.co.uk} does not
     * select research, because "who is Huw Edwards on bbc.co.uk" is a lookup and paying three
     * advanced searches for it would be wrong.
     */
    public static WebSearchProfile detect(String input) {
        if (input == null || input.isBlank()) {
            return LOOKUP;
        }
        String s = input.toLowerCase(Locale.ROOT);
        for (String marker : RESEARCH_MARKERS) {
            if (containsWord(s, marker)) {
                return RESEARCH;
            }
        }
        return LOOKUP;
    }

    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            int end = i + word.length();
            boolean startOk = i == 0 || !isWordChar(haystack.charAt(i - 1));
            boolean endOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = end;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}