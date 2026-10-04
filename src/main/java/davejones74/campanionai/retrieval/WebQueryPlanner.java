package davejones74.campanionai.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the small, fixed set of queries one request is allowed to run.
 *
 * <p>Deliberately not model generated. A query generator is an unbounded cost and a source of
 * drift between runs: the same question would search differently each time it was asked. These
 * queries are a pure function of the request, so the search count is predictable, the cost of a
 * request is knowable in advance, and a test can assert exactly what was sent.
 *
 * <p>Each variant exists for a reason:
 * <ol>
 *   <li>the request as the user phrased it, minus the hostname now carried by the scope;</li>
 *   <li>the same request reduced to its subject, dropping the instruction words, which reads to
 *       a search engine as a cleaner topic than "give me all the stories about";</li>
 *   <li>the subject plus an explicit {@code site:} operator, so the scope is asserted twice:
 *       once through {@code include_domains} and once inside the query text.</li>
 * </ol>
 */
public final class WebQueryPlanner {

    /**
     * Words that describe the request rather than its subject. Removing them turns
     * "give me all the stories about Huw Edwards" into "Huw Edwards".
     */
    private static final Set<String> INSTRUCTION_WORDS = Set.of(
            "a", "about", "all", "also", "an", "and", "any", "are", "article", "articles", "as", "at",
            "be", "bring", "can", "could", "cover", "coverage", "did", "do", "does", "digest",
            "each", "entry", "entries", "every", "everything", "find", "for", "from", "full", "get",
            "give", "gives", "has", "have", "how", "i", "in", "info", "information", "is", "it",
            "item", "items", "its", "just", "latest", "like", "link", "links", "list", "look",
            "looking", "me", "mention", "mentions", "more", "my", "need", "news", "of", "on",
            "overview", "page", "pages", "piece", "pieces", "please", "post", "posts", "publish",
            "publishes", "published", "pull", "recent", "recently", "reference", "references",
            "related", "report", "reports", "roundup", "roundups", "search", "see", "show", "site",
            "some", "story", "stories", "summary", "summaries", "tell", "the", "thing", "things",
            "to", "up", "us", "was", "we", "were", "what", "when", "where", "which", "who", "why",
            "will", "with", "would", "write", "writes", "written", "you");

    private WebQueryPlanner() {
    }

    /**
     * Plans up to {@code maxQueries} distinct queries. Always returns at least the cleaned
     * request, and never more than {@code maxQueries}.
     *
     * @param query the request text, already stripped of any hostname
     * @param host  the scope host, or {@code null} when the search is not site-restricted
     */
    public static List<String> plan(String query, String host, int maxQueries) {
        List<String> planned = new ArrayList<>();
        String cleaned = tidy(query);
        if (maxQueries <= 0 || cleaned.isBlank()) {
            return List.of();
        }
        planned.add(cleaned);

        String subject = tidy(subject(query));
        if (maxQueries > 1 && !subject.isBlank() && !subject.equalsIgnoreCase(cleaned)) {
            planned.add(subject);
        }
        if (maxQueries > 2 && !subject.isBlank() && host != null && !host.isBlank()) {
            planned.add(subject + " site:" + host);
        }

        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String q : planned) {
            if (distinct.size() >= maxQueries) {
                break;
            }
            if (q != null && !q.isBlank()) {
                distinct.add(q);
            }
        }
        return List.copyOf(distinct);
    }

    /** The request with instruction words removed, falling back to the request itself. */
    static String subject(String query) {
        if (query == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String token : query.trim().split("\\s+")) {
            String bare = token.replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}]+$", "");
            if (bare.isBlank()) {
                continue;
            }
            if (INSTRUCTION_WORDS.contains(bare.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(bare);
        }
        String result = kept.toString();
        return result.isBlank() ? tidy(query) : result;
    }

    private static String tidy(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s{2,}", " ").replaceAll("^[\\s\\-:,]+|[\\s\\-:,]+$", "").trim();
    }
}