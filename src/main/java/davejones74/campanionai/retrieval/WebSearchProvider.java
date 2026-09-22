package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import davejones74.campanionai.WebFetcher;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web results via the Tavily Search API, purpose-built for LLM retrieval. The
 * initial implementation is a single provider so the underlying engine stays
 * swappable without touching conversation logic.
 */
public final class WebSearchProvider implements RetrievalProvider {

    private static final String DEFAULT_SEARCH_URL = "https://api.tavily.com/search";
    private static final int MAX_RESULT_CHARS = 6000;

    private final String apiKey;
    private final WebFetcher fetcher;
    private final int maxResults;
    private final int fetchPages;
    private final String searchUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(25);

    public WebSearchProvider(String apiKey, WebFetcher fetcher, int maxResults, int fetchPages) {
        this(apiKey, fetcher, maxResults, fetchPages, DEFAULT_SEARCH_URL);
    }

    WebSearchProvider(String apiKey, WebFetcher fetcher, int maxResults, int fetchPages, String searchUrl) {
        this.apiKey = apiKey;
        this.fetcher = fetcher;
        this.maxResults = Math.max(1, Math.min(10, maxResults));
        this.fetchPages = Math.max(0, Math.min(3, fetchPages));
        this.searchUrl = searchUrl;
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
        ObjectNode body = json.createObjectNode()
                .put("query", request.query() == null ? "" : request.query())
                .put("search_depth", "advanced")
                .put("topic", "news")
                .put("max_results", maxResults)
                .put("include_answer", false);
        int days = freshnessDays(request.freshness());
        if (days > 0) body.put("days", days);

        JsonNode root;
        try {
            String resp = HttpHelper.jsonPost(searchUrl, Map.of("Authorization", "Bearer " + apiKey),
                    json.writeValueAsString(body), timeout);
            root = json.readTree(resp);
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("Failed to parse web search response.",
                    "Current web information could not be retrieved.", e);
        }

        List<RetrievalItem> items = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode r : results) {
                if (items.size() >= maxResults) break;
                String url = r.path("url").asText("");
                if (url.isBlank()) continue;
                String domain = r.path("domain").asText("");
                if (domain.isBlank()) {
                    domain = extractDomain(url);
                }
                String published = r.path("published_date").asText("");
                if (published.isBlank()) published = null;
                String snippet = r.path("content").asText("");
                items.add(new RetrievalItem(domain, r.path("title").asText(""), url, published, snippet, null));
            }
        }
        return new RetrievalResult(RetrievalKind.WEB_SEARCH, Instant.now(),
                fetchPages(items));
    }

    private List<RetrievalItem> fetchPages(List<RetrievalItem> items) {
        List<RetrievalItem> out = new ArrayList<>(items);
        if (fetchPages == 0 || fetcher == null) return out;
        int fetched = 0;
        for (int i = 0; i < out.size() && fetched < fetchPages; i++) {
            RetrievalItem item = out.get(i);
            if (item.url() == null) continue;
            try {
                WebFetcher.Page page = fetcher.fetch(item.url());
                if (page.text() != null && !page.text().isBlank()) {
                    String title = item.title().isBlank() ? page.title() : item.title();
                    String text = page.text().trim();
                    if (text.length() > MAX_RESULT_CHARS) text = text.substring(0, MAX_RESULT_CHARS);
                    out.set(i, new RetrievalItem(item.source(), title, item.url(), item.published(), item.snippet(), text));
                    fetched++;
                }
            } catch (IOException ignored) {
                LOG.info("Web search page fetch failed for {}: {}", item.url(), String.valueOf(ignored.getMessage()));
            }
        }
        return out;
    }

    private static int freshnessDays(Freshness freshness) {
        if (freshness == null) return 7;
        return switch (freshness) {
            case TODAY, LIVE -> 1;
            case RECENT -> 7;
            case ANY -> 0;
        };
    }

    private static String extractDomain(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return "";
            String stripped = host.startsWith("www.") ? host.substring(4) : host;
            return stripped;
        } catch (Exception e) {
            return "";
        }
    }

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(WebSearchProvider.class);
}