package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebQueryPlannerTest {

    @Test
    void researchPlanningIsThreeQueriesAndDeterministic() {
        String request = "give me all the stories about Huw Edwards";
        List<String> first = WebQueryPlanner.plan(request, "bbc.co.uk", 3);
        List<String> second = WebQueryPlanner.plan(request, "bbc.co.uk", 3);

        assertEquals(3, first.size());
        assertEquals(first, second, "planning must be a pure function of the request");
        assertEquals(request, first.get(0));
        assertEquals("Huw Edwards", first.get(1));
        assertEquals("Huw Edwards site:bbc.co.uk", first.get(2));
    }

    @Test
    void lookupPlanningIsASingleQuery() {
        assertEquals(List.of("Huw Edwards"), WebQueryPlanner.plan("Huw Edwards", "bbc.co.uk", 1));
    }

    @Test
    void neverExceedsTheBoundItIsGiven() {
        for (int max = 0; max <= 5; max++) {
            List<String> planned = WebQueryPlanner.plan("give me all the stories about Huw Edwards",
                    "bbc.co.uk", max);
            assertTrue(planned.size() <= max, "planned " + planned.size() + " for a bound of " + max);
        }
    }

    @Test
    void unscopedResearchStillGetsComplementaryQueries() {
        List<String> planned = WebQueryPlanner.plan("list every article about climate policy", null, 3);
        assertEquals(2, planned.size(),
                "without a host the site: variant adds nothing, so two distinct queries is the ceiling");
        assertEquals("list every article about climate policy", planned.get(0));
        assertEquals("climate policy", planned.get(1));
    }

    @Test
    void subjectsDropInstructionWordsButKeepMeaning() {
        assertEquals("Huw Edwards", WebQueryPlanner.subject("give me all the stories about Huw Edwards"));
        assertEquals("Huw Edwards interviews", WebQueryPlanner.subject("show me Huw Edwards interviews"));
        assertEquals("AI models", WebQueryPlanner.subject("what is the latest news on AI models?"));
    }

    @Test
    void subjectFallsBackToTheRequestWhenEverythingIsStripped() {
        assertEquals("latest news", WebQueryPlanner.subject("latest news"));
    }

    @Test
    void blankRequestPlansNothing() {
        assertTrue(WebQueryPlanner.plan("  ", "bbc.co.uk", 3).isEmpty());
        assertTrue(WebQueryPlanner.plan(null, "bbc.co.uk", 3).isEmpty());
    }
}