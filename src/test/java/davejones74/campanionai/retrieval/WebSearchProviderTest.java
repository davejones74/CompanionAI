package davejones74.campanionai.retrieval;

import davejones74.campanionai.WebFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchProviderTest {

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
        }
    }

    @Test
    void providerNeedsConfiguredKey() throws Exception {
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
}