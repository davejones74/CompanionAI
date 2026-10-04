package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebScopeTest {

    private static WebScope scopeOf(String input) {
        Optional<WebScope> s = WebScope.extract(input);
        assertTrue(s.isPresent(), "expected a scope in: " + input);
        return s.get();
    }

    @Test
    void extractsBareDomainAndKeepsTheRestOfTheRequest() {
        WebScope scope = scopeOf("bbc.co.uk - give me all the stories about Huw Edwards");
        assertEquals("bbc.co.uk", scope.host());
        assertEquals("give me all the stories about Huw Edwards", scope.remainder());
    }

    @Test
    void keepsTheSpecificSubdomainTheUserSupplied() {
        assertEquals("news.bbc.co.uk", scopeOf("news.bbc.co.uk - stories about Huw Edwards").host());
        assertEquals("sports.bbc.co.uk", scopeOf("sports.bbc.co.uk stories about the champions").host());
    }

    @Test
    void normalisesCase() {
        assertEquals("bbc.co.uk", scopeOf("BBC.CO.UK - stories about X").host());
    }

    @Test
    void understandsTheSiteOperator() {
        WebScope scope = scopeOf("site:bbc.co.uk Huw Edwards");
        assertEquals("bbc.co.uk", scope.host());
        assertEquals("Huw Edwards", scope.remainder());
    }

    @Test
    void doesNotTreatAFullUrlAsAScope() {
        assertTrue(WebScope.extract("summarise https://www.bbc.co.uk/news/articles/xyz").isEmpty(),
                "a scheme-qualified URL is a page to fetch, not a site to search");
    }

    @Test
    void doesNotTreatAnEmailAddressAsAScope() {
        assertTrue(WebScope.extract("email reporter@bbc.co.uk about the story").isEmpty());
    }

    @Test
    void ignoresFilenamesAndVersionsAndDates() {
        assertTrue(WebScope.extract("summarise report.docx").isEmpty());
        assertTrue(WebScope.extract("open notes.md please").isEmpty());
        assertTrue(WebScope.extract("check index.html").isEmpty());
        assertTrue(WebScope.extract("what is in qwen3.6:27b").isEmpty());
        assertTrue(WebScope.extract("published 2024.10.01").isEmpty());
        assertTrue(WebScope.extract("use e.g. this value").isEmpty());
    }

    @Test
    void ignoresTextWithNoHostAtAll() {
        assertTrue(WebScope.extract("give me all the stories about Huw Edwards").isEmpty());
        assertTrue(WebScope.extract("").isEmpty());
        assertTrue(WebScope.extract(null).isEmpty());
    }

    @Test
    void scopeMatchesTheHostAndItsSubdomains() {
        WebScope base = scopeOf("bbc.co.uk x");
        assertTrue(base.containsHost("bbc.co.uk"));
        assertTrue(base.containsHost("www.bbc.co.uk"));
        assertTrue(base.containsHost("news.bbc.co.uk"));
        assertTrue(base.containsHost("https://www.bbc.co.uk/news/articles/1"));
    }

    @Test
    void specificScopeNeverAdmitsASiblingHost() {
        WebScope news = scopeOf("news.bbc.co.uk x");
        assertTrue(news.containsHost("news.bbc.co.uk"));
        assertFalse(news.containsHost("www.bbc.co.uk"),
                "asking for news.bbc.co.uk must not pull in www.bbc.co.uk");
        assertFalse(news.containsHost("bbc.co.uk"));
    }

    @Test
    void scopeRejectsLookalikeAndEmptyHosts() {
        WebScope base = scopeOf("bbc.co.uk x");
        assertFalse(base.containsHost("notbbc.co.uk"), "a substring match is not a subdomain match");
        assertFalse(base.containsHost("bbc.co.uk.evil.example"));
        assertFalse(base.containsHost(""));
        assertFalse(base.containsHost(null));
    }
}