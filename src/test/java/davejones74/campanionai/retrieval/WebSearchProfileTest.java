package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSearchProfileTest {

    @Test
    void broadWordingSelectsResearch() {
        assertEquals(WebSearchProfile.RESEARCH,
                WebSearchProfile.detect("bbc.co.uk - give me all the stories about Huw Edwards"));
        assertEquals(WebSearchProfile.RESEARCH, WebSearchProfile.detect("list every article about X"));
        assertEquals(WebSearchProfile.RESEARCH, WebSearchProfile.detect("find all the posts about X"));
        assertEquals(WebSearchProfile.RESEARCH, WebSearchProfile.detect("give me everything on X"));
        assertEquals(WebSearchProfile.RESEARCH, WebSearchProfile.detect("comprehensive coverage of X"));
    }

    @Test
    void pointedQuestionsStayOnLookup() {
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("what is the latest news on AI models?"));
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("who is Huw Edwards"));
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("bbc.co.uk - who is Huw Edwards"));
    }

    @Test
    void markersMatchOnWordBoundariesOnly() {
        // "all" is a substring of these, and none of them asks for a sweep.
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("how did Manchester United play in the football"));
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("finally, what is the weather in Exeter?"));
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("what is a small business tax relief?"));
    }

    @Test
    void blankInputIsALookup() {
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect(null));
        assertEquals(WebSearchProfile.LOOKUP, WebSearchProfile.detect("   "));
    }
}