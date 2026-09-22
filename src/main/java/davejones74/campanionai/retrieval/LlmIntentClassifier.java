package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import davejones74.campanionai.LlmClient;
import davejones74.campanionai.Tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses the local LLM to classify a question and extract optional hints
 * (location for weather, team for sports, past days). Never throws: any LLM or
 * parse failure yields {@link Intent#NONE}, the safe default.
 */
public final class LlmIntentClassifier implements IntentClassifier {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*?}", Pattern.DOTALL);

    private final LlmClient llm;
    private final ObjectMapper json = new ObjectMapper();
    private final int historyTokens;

    public LlmIntentClassifier(LlmClient llm) {
        this(llm, 1500);
    }

    public LlmIntentClassifier(LlmClient llm, int historyTokens) {
        this.llm = llm;
        this.historyTokens = Math.max(0, historyTokens);
    }

    @Override
    public Optional<IntentClassification> classify(String input, List<LlmClient.ChatMessage> history) {
        try {
            List<LlmClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new LlmClient.ChatMessage("system", SYSTEM_PROMPT));
            int used = 0;
            for (LlmClient.ChatMessage m : history) {
                int t = Tokens.estimate(m.content());
                if (used + t > historyTokens) break;
                messages.add(m);
                used += t;
            }
            messages.add(new LlmClient.ChatMessage("user", input));
            String reply = llm.chat(messages);
            return Optional.of(parse(reply));
        } catch (Exception e) {
            return Optional.of(new IntentClassification(Intent.NONE));
        }
    }

    private IntentClassification parse(String reply) {
        try {
            Matcher m = JSON_BLOCK.matcher(reply);
            if (!m.find()) {
                return new IntentClassification(Intent.NONE);
            }
            JsonNode node = json.readTree(m.group());
            Intent intent = toIntent(node.path("intent").asText(""));
            String location = emptyToNull(node.path("location").asText(""));
            String team = emptyToNull(node.path("team").asText(""));
            int past = node.path("past").asInt(0);
            return new IntentClassification(intent, location, team, past);
        } catch (Exception e) {
            return new IntentClassification(Intent.NONE);
        }
    }

    private static Intent toIntent(String s) {
        if (s == null) return Intent.NONE;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "web_search", "web-search" -> Intent.WEB_SEARCH;
            case "weather" -> Intent.WEATHER;
            case "sports" -> Intent.SPORTS;
            case "knowledge_base", "knowledge-base", "knowledge base" -> Intent.KNOWLEDGE_BASE;
            default -> Intent.NONE;
        };
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final String SYSTEM_PROMPT = """
            You are the routing component of a chat assistant. Classify ONLY the user's latest message.
            Reply with a single JSON object and nothing else, using this shape:
            {"intent":"none|web_search|weather|sports|knowledge_base","location":"","team":"","past":0}

            Rules:
            - none: the message can be answered from the conversation itself, or is general knowledge that does not need fresh data.
            - knowledge_base: the message refers to uploaded documents or previously fetched articles.
            - web_search: the message asks for current or recent news/developments (UK affairs, cars, technology, gaming, AI). Fill "location" if a place is named.
            - weather: the message asks about current, future or past weather or temperature. Fill "location" with the place if any is named, and "past":1 if it asks about yesterday.
            - sports: the message asks about a football result, score, fixture or table. Fill "team" with the club or national side mentioned (e.g. "Arsenal", "England").
            - If the question can clearly be answered from the recent conversation, use none rather than triggering a search.
            Return only the JSON object.""";
}