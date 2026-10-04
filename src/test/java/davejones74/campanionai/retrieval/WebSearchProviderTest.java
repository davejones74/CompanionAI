package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import davejones74.campanionai.WebFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provider-level behaviour: what is sent to the engine, and what survives coming back.
 *
 * <p>Tests that assert on the request use a stub endpoint and zero page fetches. A scope test has
 * to name a host that passes scope enforcement, and using a real hostname there would make the
 * suite reach the public internet to fetch page bodies.
 */
class WebSearchProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WebSearchProfileSettings lookupNoFetch() {
        return new WebSearchProfileSettings(5, 0, 1, "basic", "news", "week");
    }

    private static WebSearchProfileSettings researchNoFetch() {
        return new WebSearchProfileSettings(20, 0, 3, "advanced", "general", null);
    }

    private static JsonNode body(StubServer s, int index) throws Exception {
        return JSON.readTree(s.bodies("/search").get(index));
    }

    private static String result(String title, String url) {
        return "{\"title\":\"" + title + "\",\"url\":\"" + url + "\",\"content\":\"snippet\"}";
    }

    @Test
    void parsesSearchResultsAndFetchesPages() throws Exception {
        try (StubServer s = new StubServer()) {
            String page1 = s.url() + "/art1";
            String page2 = s.url() + "/art2";
            s.on("/search", "{\"results\":["
                    + "{\"title\":\"Breaking news\",\"url\":\"" + page1 + "\",\"domain\":\"news.example.com\","
                    + "\"published_date\":\"2026-09-22T08:00:00.000Z\",\"content\":\"snippet one\"},"
                    + "{\"title\":\"Second story\",\"url\":\"" + page2 + "\",\"domain\":\"tech.example.com\","
                    + "\"published_date\":\"2026-09-21T10:00:00.000Z\",\"content\":\"snippet two\"}"
                    + "]}");
            s.on("/art1", "Body of the first live article fetched as transient content.");
            s.on("/art2", "Body of the second live article.");

            WebSearchProvider p = new WebSearchProvider("tavily-key",
                    new WebFetcher(2_097_152, true), 5, 1, s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "What is the latest on UK affairs?", RetrievalKind.WEB_SEARCH, null, Freshness.RECENT, 0, null));

            List<RetrievalItem> items = r.items();
            assertEquals(2, items.size());
            RetrievalItem first = items.get(0);
            assertEquals("news.example.com", first.source());
            assertEquals("Breaking news", first.title());
            assertEquals("2026-09-22T08:00:00.000Z", first.published());
            assertTrue(first.content().contains("first live article"), String.valueOf(first.content()));
            RetrievalItem second = items.get(1);
            assertNull(second.content(), "second item should not have been page-fetched");
            assertEquals(1, r.pagesFetched());
        }
    }

    @Test
    void providerNeedsConfiguredKey() {
        WebSearchProvider none = new WebSearchProvider("  ",
                new WebFetcher(2_097_152, true), 5, 0, "http://127.0.0.1:1/search");
        assertFalse(none.isConfigured());
        WebSearchProvider ready = new WebSearchProvider("key",
                new WebFetcher(2_097_152, true), 5, 0, "http://127.0.0.1:1/search");
        assertTrue(ready.isConfigured());
    }

    @Test
    void capsResultsAtMax() throws Exception {
        try (StubServer s = new StubServer()) {
            StringBuilder body = new StringBuilder("{\"results\":[");
            for (int i = 0; i < 12; i++) {
                if (i > 0) body.append(',');
                body.append("{\"title\":\"r").append(i).append("\",\"url\":\"").append(s.url()).append("/r").append(i)
                        .append("\",\"domain\":\"x.com\",\"content\":\"snippet\"}");
            }
            body.append("]}");
            s.on("/search", body.toString());
            WebSearchProvider p = new WebSearchProvider("key",
                    new WebFetcher(2_097_152, true), 3, 0, s.url() + "/search");
            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "query", RetrievalKind.WEB_SEARCH, null, Freshness.RECENT, 0, null));
            assertEquals(3, r.items().size());
        }
    }

    @Test
    void lookupSendsOneCheapRecentSearchWithNoDays() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":[]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            p.retrieve(new RetrievalRequest("latest news on AI", RetrievalKind.WEB_SEARCH, null,
                    Freshness.RECENT, 0, null, null, WebSearchProfile.LOOKUP));

            assertEquals(1, s.hits("/search"));
            JsonNode sent = body(s, 0);
            assertEquals("basic", sent.path("search_depth").asText());
            assertEquals("news", sent.path("topic").asText());
            assertEquals("week", sent.path("time_range").asText());
            assertEquals(5, sent.path("max_results").asInt());
            assertFalse(sent.has("days"), "days is accepted but ignored by the engine, so it must not be sent");
            assertTrue(sent.path("include_domains").isMissingNode(), "no scope was requested");
        }
    }

    @Test
    void researchSendsThreeAdvancedSearchesRestrictedToTheHost() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":[]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "give me all the stories about Huw Edwards", RetrievalKind.WEB_SEARCH, null,
                    Freshness.ANY, 0, null, "bbc.co.uk", WebSearchProfile.RESEARCH));

            assertEquals(3, s.hits("/search"), "research is a fixed three-query sweep");
            assertEquals(3, r.queries().size());
            assertEquals("give me all the stories about Huw Edwards", r.queries().get(0));
            assertEquals("Huw Edwards", r.queries().get(1));
            assertEquals("Huw Edwards site:bbc.co.uk", r.queries().get(2));

            for (int i = 0; i < 3; i++) {
                JsonNode sent = body(s, i);
                assertEquals("advanced", sent.path("search_depth").asText());
                assertEquals(20, sent.path("max_results").asInt());
                assertEquals("bbc.co.uk", sent.path("include_domains").get(0).asText());
                assertFalse(sent.has("days"));
                assertFalse(sent.has("time_range"),
                        "research must not inherit a recency window it never asked for");
            }
        }
    }

    @Test
    void theSuppliedHostnameIsSentVerbatimAndNeverBroadened() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":[]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            p.retrieve(new RetrievalRequest("stories about Huw Edwards", RetrievalKind.WEB_SEARCH, null,
                    Freshness.RECENT, 0, null, "news.bbc.co.uk", WebSearchProfile.RESEARCH));

            for (String sent : s.bodies("/search")) {
                assertEquals("news.bbc.co.uk", JSON.readTree(sent).path("include_domains").get(0).asText(),
                        "the specific host must be sent as given, never widened to its parent");
            }
        }
    }

    @Test
    void dropsResultsOutsideTheScopeEvenWhenTheEngineReturnsThem() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":["
                    + result("In scope", "https://www.bbc.co.uk/news/articles/1") + ","
                    + result("Out of scope", "https://www.cnn.com/2026/09/22/story") + ","
                    + result("Lookalike", "https://notbbc.co.uk/news/2") + ","
                    + result("Suffix trap", "https://bbc.co.uk.evil.example/news/3") + ","
                    + result("Deeper subdomain", "https://sport.bbc.co.uk/football/4")
                    + "]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest("stories", RetrievalKind.WEB_SEARCH, null,
                    Freshness.RECENT, 0, null, "bbc.co.uk", WebSearchProfile.LOOKUP));

            assertEquals(List.of("https://www.bbc.co.uk/news/articles/1",
                            "https://sport.bbc.co.uk/football/4"),
                    r.items().stream().map(RetrievalItem::url).toList());
        }
    }

    @Test
    void aSpecificScopeNeverWidensToASiblingHost() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":["
                    + result("Sibling", "https://www.bbc.co.uk/news/articles/1") + ","
                    + result("Exact", "https://news.bbc.co.uk/news/articles/2")
                    + "]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest("stories", RetrievalKind.WEB_SEARCH, null,
                    Freshness.RECENT, 0, null, "news.bbc.co.uk", WebSearchProfile.LOOKUP));

            assertEquals(List.of("https://news.bbc.co.uk/news/articles/2"),
                    r.items().stream().map(RetrievalItem::url).toList());
        }
    }

    @Test
    void anEmptyScopedResultStaysEmptyRatherThanRetryingWithoutTheScope() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":[]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest("stories about a topic nobody covers",
                    RetrievalKind.WEB_SEARCH, null, Freshness.RECENT, 0, null, "bbc.co.uk",
                    WebSearchProfile.LOOKUP));

            assertEquals(0, r.resultsReturned());
            assertEquals(0, r.pagesFetched());
            assertEquals("bbc.co.uk", r.domain());
            assertEquals(1, s.hits("/search"), "no unbroadened retry may be attempted");
        }
    }

    @Test
    void mergesResultsAcrossQueriesWithoutDuplicatingTheSamePage() throws Exception {
        try (StubServer s = new StubServer()) {
            // Every query returns the same story under a different tracking suffix, plus one new page.
            s.on("/search", "{\"results\":["
                    + result("Same story", "https://www.bbc.co.uk/news/1?utm_source=a")
                    + ","
                    + result("New page", "https://www.bbc.co.uk/news/2") + ","
                    + result("Same story again", "https://bbc.co.uk/news/1/#top")
                    + "]}");
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    lookupNoFetch(), researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest("all the stories about Huw Edwards",
                    RetrievalKind.WEB_SEARCH, null, Freshness.ANY, 0, null, "bbc.co.uk",
                    WebSearchProfile.RESEARCH));

            assertEquals(3, s.hits("/search"));
            assertEquals(2, r.resultsReturned(), "the same story arrived three times and must count once");
            assertEquals(3, r.queries().size());
        }
    }

    @Test
    void countsOnlyThePagesItActuallyFetched() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/search", "{\"results\":["
                    + result("A", s.url() + "/a") + ","
                    + result("B", s.url() + "/b") + ","
                    + result("C", s.url() + "/c") + "]}");
            s.on("/a", "Page A body.");
            s.on("/b", "Page B body.");
            // /c is left unregistered so that fetch fails and must not be counted.
            WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                    new WebSearchProfileSettings(10, 2, 1, "basic", "general", null),
                    researchNoFetch(), s.url() + "/search");

            RetrievalResult r = p.retrieve(new RetrievalRequest("stories", RetrievalKind.WEB_SEARCH, null,
                    Freshness.RECENT, 0, null, "127.0.0.1", WebSearchProfile.LOOKUP));

            assertEquals(3, r.resultsReturned());
            assertEquals(2, r.pagesFetched(), "only the two readable pages count as evidence");
            assertEquals(2, r.items().stream().filter(i -> i.content() != null).count());
        }
    }

    @Test
    void aFailureOnTheFirstQueryAbortsTheWholeSweep() {
        // 127.0.0.1:1 refuses the connection, so the first query fails hard.
        WebSearchProvider p = new WebSearchProvider("key", new WebFetcher(2_097_152, true),
                lookupNoFetch(), researchNoFetch(), "http://127.0.0.1:1/search");
        try {
            p.retrieve(new RetrievalRequest("all the stories", RetrievalKind.WEB_SEARCH, null,
                    Freshness.ANY, 0, null, "bbc.co.uk", WebSearchProfile.RESEARCH));
            fail("expected the lookup to fail");
        } catch (RetrievalException expected) {
            assertEquals("Current web information could not be retrieved.", expected.userFacingMessage());
        }
    }
}