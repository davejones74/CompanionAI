package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WebUrlsTest {

    @Test
    void wwwAndTrailingSlashAreTheSamePage() {
        assertEquals(WebUrls.canonicalKey("https://www.bbc.co.uk/news/articles/1"),
                WebUrls.canonicalKey("https://bbc.co.uk/news/articles/1/"));
        assertEquals(WebUrls.canonicalKey("https://www.bbc.co.uk/news/articles/1"),
                WebUrls.canonicalKey("https://WWW.BBC.CO.UK/news/articles/1"));
    }

    @Test
    void fragmentsDoNotMakeANewPage() {
        assertEquals(WebUrls.canonicalKey("https://bbc.co.uk/a"),
                WebUrls.canonicalKey("https://bbc.co.uk/a#comments"));
    }

    @Test
    void campaignParametersDoNotMakeANewPage() {
        assertEquals(WebUrls.canonicalKey("https://bbc.co.uk/news/1"),
                WebUrls.canonicalKey("https://bbc.co.uk/news/1?utm_source=twitter&utm_medium=social"));
        assertEquals(WebUrls.canonicalKey("https://bbc.co.uk/news/1"),
                WebUrls.canonicalKey("https://bbc.co.uk/news/1?fbclid=abc123"));
    }

    @Test
    void meaningfulParametersAndPathsAreDistinguished() {
        assertNotEquals(WebUrls.canonicalKey("https://bbc.co.uk/news/1"),
                WebUrls.canonicalKey("https://bbc.co.uk/news/2"));
        assertNotEquals(WebUrls.canonicalKey("https://bbc.co.uk/search?q=huw"),
                WebUrls.canonicalKey("https://bbc.co.uk/search?q=climate"));
    }

    @Test
    void hostIsExtractedForScopeEnforcement() {
        assertEquals("www.bbc.co.uk", WebUrls.host("https://www.bbc.co.uk/news/articles/1"));
        assertEquals("", WebUrls.host("not a url"));
        assertEquals("", WebUrls.host(null));
    }
}