package davejones74.campanionai.retrieval;

import davejones74.campanionai.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleIntentClassifierTest {

    private final RuleIntentClassifier classifier = new RuleIntentClassifier();
    private final List<LlmClient.ChatMessage> emptyHistory = List.of();

    private IntentClassification classify(String input) {
        Optional<IntentClassification> c = classifier.classify(input, emptyHistory);
        assertTrue(c.isPresent(), "input should be classified: " + input);
        return c.get();
    }

    @Test
    void weatherQuestions() {
        IntentClassification c = classify("what's the weather in Exeter?");
        assertEquals(Intent.WEATHER, c.intent());
        assertEquals("Exeter", c.location());
    }

    @Test
    void weatherDefaultsToDefaultLocationWhenNoneNamed() {
        IntentClassification c = classify("is it going to rain tomorrow?");
        assertEquals(Intent.WEATHER, c.intent());
        assertNull(c.location());
    }

    @Test
    void yesterdayWeatherAsksForPastDay() {
        IntentClassification c = classify("what was the weather like yesterday?");
        assertEquals(1, c.pastDays());
    }

    @Test
    void footballQuestions() {
        IntentClassification c = classify("how did Manchester United get on at the weekend?");
        assertEquals(Intent.SPORTS, c.intent());
        assertEquals("Manchester United", c.team());
    }

    @Test
    void leagueTableQuestions() {
        IntentClassification c = classify("who is top of the premier league table?");
        assertEquals(Intent.SPORTS, c.intent());
        assertNull(c.team());
    }

    @Test
    void eplResultsQuestion() {
        IntentClassification c = classify("What's the latest English Premier League results.");
        assertEquals(Intent.SPORTS, c.intent());
        assertNull(c.team());
    }

    @Test
    void eplAbbreviationQuestion() {
        IntentClassification c = classify("latest EPL results");
        assertEquals(Intent.SPORTS, c.intent());
        assertNull(c.team());
    }

    @Test
    void teamScoreQuestion() {
        IntentClassification c = classify("what was the final score for Chelsea tonight?");
        assertEquals(Intent.SPORTS, c.intent());
        assertEquals("Chelsea", c.team());
    }

    @Test
    void webNewsQuestions() {
        IntentClassification c = classify("what is the latest news on AI models?");
        assertEquals(Intent.WEB_SEARCH, c.intent());
        assertNull(c.location());
    }

    @Test
    void locationNamedInNewsQuery() {
        IntentClassification c = classify("latest developments in the UK?");
        assertEquals(Intent.WEB_SEARCH, c.intent());
        assertEquals("UK", c.location());
    }

    @Test
    void ukHeadlinesQuery() {
        IntentClassification c = classify("What are the top headlines in the UK");
        assertEquals(Intent.WEB_SEARCH, c.intent());
        assertEquals("UK", c.location());
    }

    @Test
    void knowledgeBaseQuestions() {
        IntentClassification c = classify("summarise what I uploaded earlier");
        assertEquals(Intent.KNOWLEDGE_BASE, c.intent());
    }

    @Test
    void conversationalQuestionsDeferToNone() {
        Optional<IntentClassification> c = classifier.classify("hello there", emptyHistory);
        assertTrue(c.isPresent());
        assertEquals(Intent.NONE, c.get().intent());
    }
}