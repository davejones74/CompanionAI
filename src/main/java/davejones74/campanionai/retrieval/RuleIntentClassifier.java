package davejones74.campanionai.retrieval;

import davejones74.campanionai.llm.LlmMessage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Deterministic, cheap fast-path for obviously classified questions. Also
 * detects the most common team names for sports. Returns empty when unsure, so
 * the caller can fall back to the LLM classifier.
 */
public final class RuleIntentClassifier implements IntentClassifier {

    private static final Set<String> TEAMS = Set.of(
            "arsenal", "chelsea", "liverpool", "manchester united", "man utd", "man united",
            "manchester city", "man city", "tottenham", "spurs", "newcastle", "aston villa",
            "west ham", "everton", "brighton", "wolves", "wolverhampton", "crystal palace",
            "fulham", "brentford", "bournemouth", "nottingham forest", "leeds", "leicester",
            "southampton", "sunderland", "celtic", "rangers", "england", "scotland", "wales");

    private static final Set<String> WEATHER_WORDS = Set.of(
            "weather", "forecast", "raining", "rain", "sunny", "snowing", "temperature",
            "humidity", "windy", "storm", "cloudy", "met office", "degrees");

    private static final Set<String> WEB_WORDS = Set.of(
            "news", "headlines", "headline", "announcement", "announced", "developments",
            "latest");

    /**
     * Explicit search requests. Matched on word boundaries.
     *
     * <p>Distinct from {@link #WEB_WORDS}, which is a substring test kept for compatibility
     * because it is what shipped. A substring test is wrong for search verbs: "research" sits
     * inside "researcher", and "browse" inside "browsing history".
     */
    private static final Set<String> SEARCH_WORDS = Set.of(
            "search", "searched", "searching", "browse", "browsing", "google", "research",
            "lookup", "look up", "investigating", "investigate");

    /**
     * Requests for a sweep rather than a single answer. These select the research profile, and
     * they also indicate a web lookup is wanted at all, since "list all the articles about X"
     * names no keyword that the older word list recognised.
     *
     * <p>Every entry either names a unit of content ("stories", "articles", "posts") or is an
     * intensifier that only appears when a sweep is meant ("exhaustive", "complete"). Bare
     * quantifiers are deliberately absent, because they occur far more often in ordinary
     * conversation than in a search request: treating "thanks, that's all" or "that covers
     * everything" as a research request spent three searches answering a sign-off. A message
     * built only from such a word is genuinely ambiguous, and letting it fall through to the
     * LLM classifier is both cheaper and safer than guessing a sweep here.
     */
    private static final Set<String> RESEARCH_WORDS = Set.of(
            "entire", "exhaustive", "comprehensive", "complete", "coverage", "roundup",
            "story", "stories", "article", "articles", "piece", "pieces",
            "post", "posts", "video", "videos", "published");

    private static final Set<String> LEAGUES = Set.of(
            "premier league", "championship", "la liga", "serie a", "bundesliga", "ligue 1");

    private static final Set<String> KNOWLEDGE_WORDS = Set.of(
            "i told you", "i told", "did i", "do you remember", "remember when",
            "what did i", "what did you tell me", "what did you say", "you said",
            "i uploaded", "document");

    private static final Set<String> CONVERSATIONAL = Set.of(
            "hello", "hi ", "hey", "thanks", "thank you", "good morning",
            "good afternoon", "good evening", "how are you", "what can you do",
            "who are you", "goodbye", "bye", "help me");

    /**
     * A URL the user pasted. It is removed before any web wording is matched, because a path like
     * {@code /articles/2026/09/story} would otherwise read as a request for a sweep of articles.
     * The page itself is fetched by the caller; the topic is whatever the surrounding prose says.
     */
    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("https?://\\S+");

    @Override
    public Optional<IntentClassification> classify(String input, List<LlmMessage> history) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String s = input.toLowerCase(Locale.ROOT);
        String topic = URL.matcher(s).replaceAll(" ");
        int pastDays = 0;
        if (s.contains("yesterday")) {
            pastDays = 1;
        }

        for (String w : KNOWLEDGE_WORDS) {
            if (s.contains(w)) {
                return Optional.of(new IntentClassification(Intent.KNOWLEDGE_BASE));
            }
        }
        if (s.contains("weather") || s.contains("forecast") || s.contains("raining")
                || s.contains("temperatur") || s.contains("sunny") || s.contains("snow")
                || s.contains("degrees") || s.contains("windy") || s.contains("humidity")
                || s.contains("t-shirt") || s.contains("coat") || s.contains("umbrella")
                || hasWord(s, "rain")) {
            return Optional.of(new IntentClassification(Intent.WEATHER, location(s), null, pastDays));
        }

        String team = null;
        for (String t : TEAMS) {
            if (s.contains(t)) {
                team = canonical(t);
                break;
            }
        }
        boolean leagueMention = isLeague(s);
        boolean football = s.contains("football") || s.contains("soccer") || leagueMention || team != null;
        boolean tableIntent = football && (s.contains("table") || s.contains("standing") || s.contains("position"));
        boolean scoreMention = football && (s.contains("result") || s.contains("score") || s.contains("fixture"));
        if (team != null || leagueMention || tableIntent || scoreMention) {
            return Optional.of(new IntentClassification(Intent.SPORTS, null, team, 0));
        }

        for (String w : WEB_WORDS) {
            if (topic.contains(w)) {
                return Optional.of(new IntentClassification(Intent.WEB_SEARCH, location(topic), null, 0));
            }
        }
        // Boundary-matched so that a hostname cannot be misread as a topic word, and so that
        // "give me all the stories about X" is recognised without relying on the substring list.
        for (String w : SEARCH_WORDS) {
            if (hasWord(topic, w)) {
                return Optional.of(new IntentClassification(Intent.WEB_SEARCH, location(topic), null, 0));
            }
        }
        for (String w : RESEARCH_WORDS) {
            if (hasWord(topic, w)) {
                return Optional.of(new IntentClassification(Intent.WEB_SEARCH, location(topic), null, 0));
            }
        }
        for (String w : CONVERSATIONAL) {
            if (s.contains(w)) {
                return Optional.of(new IntentClassification(Intent.NONE));
            }
        }
        return Optional.empty();
    }

    private static String location(String s) {
        int idx = s.indexOf(" in ");
        if (idx < 0) idx = s.indexOf(" for ");
        if (idx < 0) return null;
        String after = s.substring(idx + 4).trim();
        String first = after.split("[.,;:!?]| and ")[0].trim();
        String[] words = first.split("\\s+");
        if (words.length == 0) return null;
        int start = 0;
        if (words.length > 1 && isArticle(words[0])) start = 1;
        if (start >= words.length) return null;
        boolean single = words.length - start == 1;
        StringJoiner joiner = new StringJoiner(" ");
        for (int i = start; i < words.length; i++) {
            String w = words[i];
            if (isStopWord(w)) break;
            joiner.add(single && w.length() <= 3
                    ? w.toUpperCase(Locale.ROOT)
                    : w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1));
        }
        String loc = joiner.toString();
        return loc.isBlank() ? null : loc;
    }

    private static boolean isStopWord(String w) {
        return switch (w) {
            case "the", "a", "an", "today", "tomorrow", "yesterday", "now", "at",
                    "this", "next", "weather", "forecast", "rain", "weekend", "week",
                    "on", "of", "about", "in", "for", "please", "like", "going", "wants", "want" -> true;
            default -> false;
        };
    }

    private static boolean isArticle(String w) {
        return w.equals("the") || w.equals("a") || w.equals("an");
    }

    private static boolean hasWord(String s, String word) {
        int i = 0;
        while ((i = s.indexOf(word, i)) >= 0) {
            boolean boundaryStart = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
            int end = i + word.length();
            boolean boundaryEnd = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
            if (boundaryStart && boundaryEnd) return true;
            i = end;
        }
        return false;
    }

    private static boolean isLeague(String s) {
        if (s.contains("epl") || s.contains("english premier")) return true;
        for (String l : LEAGUES) {
            if (s.contains(l)) return true;
        }
        return false;
    }

    private static String canonical(String team) {
        return switch (team) {
            case "man utd", "man united", "manchester united" -> "Manchester United";
            case "man city", "manchester city" -> "Manchester City";
            case "spurs" -> "Tottenham";
            case "wolves" -> "Wolverhampton";
            case "aston villa" -> "Aston Villa";
            case "west ham" -> "West Ham";
            case "crystal palace" -> "Crystal Palace";
            case "nottingham forest" -> "Nottingham Forest";
            case "england", "scotland", "wales" -> team.substring(0, 1).toUpperCase(Locale.ROOT) + team.substring(1);
            default -> team.substring(0, 1).toUpperCase(Locale.ROOT) + team.substring(1);
        };
    }
}