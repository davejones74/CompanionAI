package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import davejones74.campanionai.WebFetcher;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web results via the Tavily Search API, purpose-built for LLM retrieval. The
 * initial implementation is a single provider so the underlying engine stays
 * swappable without touching conversation logic.
 *
 * <p>Two profiles are supported, chosen by {@link RetrievalRequest#profile()}. A lookup runs one
 * cheap search; a research request runs a fixed, small number of complementary searches and
 * fetches pages for real evidence. Both are bounded here, in code, so the cost of a request is a
 * property of the code rather than of configuration that could be widened by mistake.
 *
 * <p>No date arithmetic is sent. The engine's {@code days} parameter was verified to be accepted
 * but ignored, which is why the previous seven-day window silently did nothing; the recency of a
 * search is now expressed with {@code time_range} from the profile, or omitted entirely.
 */
public final class WebSearchProvider implements RetrievalProvider {

    private static final String DEFAULT_SEARCH_URL = "https://api.tavily.com/search";
    private static final int MAX_RESULT_CHARS = 6000;

    private final String apiKey;
    private final WebFetcher fetcher;
    private final WebSearchProfileSettings lookup;
    private final WebSearchProfileSettings research;
    private final String searchUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(25);

    public WebSearchProvider(String apiKey, WebFetcher fetcher, int maxResults, int fetchPages) {
        this(apiKey, fetcher, maxResults, fetchPages, DEFAULT_SEARCH_URL);
    }

    WebSearchProvider(String apiKey, WebFetcher fetcher, int maxResults, int fetchPages, String searchUrl) {
        this(apiKey, fetcher,
                new WebSearchProfileSettings(maxResults, fetchPages, 1, "basic", "news", "week"),
                new WebSearchProfileSettings(
                        Math.min(WebSearchProfileSettings.RESULT_CEILING, maxResults * 3),
                        Math.min(6, fetchPages * 3),
                        WebSearchProfile.MAX_RESEARCH_QUERIES, "advanced", "general", null),
                searchUrl);
    }

    public WebSearchProvider(String apiKey, WebFetcher fetcher,
                             WebSearchProfileSettings lookup,
                             WebSearchProfileSettings research) {
        this(apiKey, fetcher, lookup, research, DEFAULT_SEARCH_URL);
    }

    /**
     * @param searchUrl override for the search endpoint; blank uses the engine's public endpoint.
     *                  Exists so a deployment can route through a proxy and so the endpoint can be
     *                  replaced in tests without reaching the network.
     */
    public WebSearchProvider(String apiKey, WebFetcher fetcher,
                             WebSearchProfileSettings lookup,
                             WebSearchProfileSettings research,
                             String searchUrl) {
        this.apiKey = apiKey;
        this.fetcher = fetcher;
        this.lookup = lookup == null ? WebSearchProfileSettings.lookup() : lookup;
        this.research = research == null ? WebSearchProfileSettings.research() : research;
        this.searchUrl = searchUrl == null || searchUrl.isBlank() ? DEFAULT_SEARCH_URL : searchUrl;
    }

    @Override
    public RetrievalKind kind() {
        return RetrievalKind.WEB_SEARCH;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public RetrievalResult retrieve(RetrievalRequest request) throws RetrievalException {
        WebSearchProfile profile = request.profile() == null ? WebSearchProfile.LOOKUP : request.profile();
        WebSearchProfileSettings settings = settingsFor(profile);
        String host = request.isScoped() ? request.domain() : null;
        List<String> queries = WebQueryPlanner.plan(request.query(), host, settings.maxQueries());
        if (queries.isEmpty()) {
            return new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(), List.of(),
                    host, List.of(), 0);
        }

        Map<String, RetrievalItem> collected = new LinkedHashMap<>();
        List<String> performed = new ArrayList<>();
        boolean first = true;
        for (String q : queries) {
            performed.add(q);
            JsonNode root;
            try {
                root = search(q, settings, host);
            } catch (RetrievalException e) {
                // A failure on the first query means the engine or the credentials are the
                // problem, and repeating the call for each remaining variant would spend the same
                // credit for the same failure. Later failures are tolerated so that a partial
                // sweep still returns whatever it did find.
                if (first) {
                    throw e;
                }
                LOG.warn("Web research query failed, continuing: {}", String.valueOf(e.getMessage()));
                continue;
            }
            first = false;
            collect(root, host, collected, settings.maxResults());
        }

        List<RetrievalItem> items = new ArrayList<>(collected.values());
        int pagesFetched = fetchPages(items, settings.fetchPages());
        return new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(), items,
                host, performed, pagesFetched);
    }

    private WebSearchProfileSettings settingsFor(WebSearchProfile profile) {
        return profile == WebSearchProfile.RESEARCH ? research : lookup;
    }

    private JsonNode search(String query, WebSearchProfileSettings settings, String host)
            throws RetrievalException {
        ObjectNode body = json.createObjectNode()
                .put("query", query)
                .put("search_depth", settings.searchDepth())
                .put("max_results", settings.maxResults())
                .put("include_answer", false);
        if (settings.topic() != null) {
            body.put("topic", settings.topic());
        }
        if (settings.timeRange() != null) {
            body.put("time_range", settings.timeRange());
        }
        if (host != null && !host.isBlank()) {
            body.putArray("include_domains").add(host);
        }
        try {
            String resp = HttpHelper.jsonPost(searchUrl, Map.of("Authorization", "Bearer " + apiKey),
                    json.writeValueAsString(body), timeout);
            return json.readTree(resp);
        } catch (Exception e) {
            throw new RetrievalException("Failed to search the web.",
                    "Current web information could not be retrieved.", e);
        }
    }

    /**
     * Takes results from one search, keeping them only if they are in scope and not already held.
     *
     * <p>Scope is re-checked here on every result. The engine is asked to restrict by domain, but
     * the prompt must not contain an out-of-scope page even if it returns one, and the check is
     * also what makes "never widen the scope" enforceable rather than aspirational.
     */
    private void collect(JsonNode root, String host, Map<String, RetrievalItem> collected,
                         int maxResults) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return;
        }
        for (JsonNode r : results) {
            if (collected.size() >= maxResults) {
                return;
            }
            String url = r.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            String resultHost = WebUrls.host(url);
            if (host != null && !host.isBlank()
                    && !(resultHost.equals(host) || resultHost.endsWith("." + host))) {
                LOG.debug("Dropping out-of-scope result {} for scope {}", resultHost, host);
                continue;
            }
            String key = WebUrls.canonicalKey(url);
            if (key.isBlank() || collected.containsKey(key)) {
                continue;
            }
            String domain = r.path("domain").asText("");
            if (domain.isBlank()) {
                domain = resultHost.startsWith("www.") ? resultHost.substring(4) : resultHost;
            }
            String published = r.path("published_date").asText("");
            if (published.isBlank()) {
                published = null;
            }
            collected.put(key, new RetrievalItem(domain, r.path("title").asText(""), url,
                    published, r.path("content").asText(""), null));
        }
    }

    /** @return the number of pages successfully fetched */
    private int fetchPages(List<RetrievalItem> items, int limit) {
        if (limit <= 0 || fetcher == null) {
            return 0;
        }
        int fetched = 0;
        for (int i = 0; i < items.size() && fetched < limit; i++) {
            RetrievalItem item = items.get(i);
            if (item.url() == null) {
                continue;
            }
            try {
                WebFetcher.Page page = fetcher.fetch(item.url());
                if (page.text() != null && !page.text().isBlank()) {
                    String title = item.title().isBlank() ? page.title() : item.title();
                    String text = page.text().trim();
                    if (text.length() > MAX_RESULT_CHARS) {
                        text = text.substring(0, MAX_RESULT_CHARS);
                    }
                    items.set(i, new RetrievalItem(item.source(), title, item.url(),
                            item.published(), item.snippet(), text));
                    fetched++;
                }
            } catch (IOException ignored) {
                LOG.info("Web search page fetch failed for {}: {}", item.url(),
                        String.valueOf(ignored.getMessage()));
            }
        }
        return fetched;
    }

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(WebSearchProvider.class);
}