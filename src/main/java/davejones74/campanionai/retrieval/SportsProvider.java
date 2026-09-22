package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Football data via API-Football (api-sports.io). Focuses on team results,
 * upcoming fixtures and league standings. Callers supply a team or league hint
 * through {@link RetrievalRequest}.
 */
public final class SportsProvider implements RetrievalProvider {

    private static final String DEFAULT_BASE_URL = "https://v3.football.api-sports.io";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final Map<String, Integer> LEAGUES = Map.of(
            "premier league", 39,
            "championship", 40,
            "la liga", 140,
            "serie a", 135,
            "bundesliga", 78,
            "ligue 1", 61);

    private static final Set<String> TEAMS = Set.of(
            "arsenal", "chelsea", "liverpool", "manchester united", "man utd", "man united",
            "manchester city", "man city", "tottenham", "spurs", "newcastle", "aston villa",
            "west ham", "everton", "brighton", "wolves", "wolverhampton", "crystal palace",
            "fulham", "brentford", "bournemouth", "nottingham forest", "leeds", "leicester",
            "southampton", "sunderland", "celtic", "rangers", "england", "scotland", "wales");

    private final String apiKey;
    private final String baseUrl;
    private final SimpleRateLimiter rateLimiter;
    private final ObjectMapper json = new ObjectMapper();

    public SportsProvider(String apiKey, int maxRequestsPerDay) {
        this(apiKey, maxRequestsPerDay, DEFAULT_BASE_URL);
    }

    SportsProvider(String apiKey, int maxRequestsPerDay, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.rateLimiter = new SimpleRateLimiter(maxRequestsPerDay);
    }

    @Override
    public RetrievalKind kind() {
        return RetrievalKind.SPORTS;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public RetrievalResult retrieve(RetrievalRequest request) throws RetrievalException {
        rateLimiter.acquire();
        String query = request.query() == null ? "" : request.query().toLowerCase(Locale.ROOT);
        String team = request.context();
        if (team != null && team.isBlank()) team = null;
        if (team == null) team = guessTeam(query);

        TeamInfo found = team == null ? null : findTeam(team);
        boolean leagueQuery = isLeagueQuery(query);

        StringBuilder sb = new StringBuilder();
        if (found != null) {
            sb.append(fixtures(found.id(), found.name()));
            if (leagueQuery) {
                sb.append('\n').append(standingFor(found.id(), found.name(), leagueId(query)));
            }
            return result("Football: " + found.name(), sb.toString());
        }
        if (leagueQuery) {
            int leagueId = leagueId(query);
            return result("League standings", standings(leagueId));
        }
        throw new RetrievalException("Could not identify a team or league in the question.",
                "I couldn't work out which team or league you're asking about.", null);
    }

    private RetrievalResult result(String title, String content) {
        RetrievalItem item = new RetrievalItem("API-Football", title, baseUrl, null, null, content.trim());
        return new RetrievalResult(RetrievalKind.SPORTS, Instant.now(), List.of(item));
    }

    private TeamInfo findTeam(String name) throws RetrievalException {
        JsonNode root = get("/teams?search=" + url(name));
        JsonNode arr = root.path("response");
        if (arr.isArray() && !arr.isEmpty()) {
            JsonNode team = arr.get(0).path("team");
            String id = team.path("id").asText("");
            String nm = team.path("name").asText(name);
            if (!id.isBlank()) return new TeamInfo(Integer.parseInt(id), nm);
        }
        return null;
    }

    private String fixtures(int teamId, String teamName) throws RetrievalException {
        JsonNode root = get("/fixtures?team=" + teamId + "&last=1&next=1");
        JsonNode arr = root.path("response");
        StringBuilder out = new StringBuilder();
        JsonNode finished = null;
        JsonNode upcoming = null;
        if (arr.isArray()) {
            for (JsonNode f : arr) {
                String status = f.path("fixture").path("status").path("short").asText("");
                if (isFinished(status)) finished = f;
                else if (isUpcoming(status)) upcoming = f;
            }
        }
        if (finished != null) {
            out.append(teamName).append(" last match: ").append(describeMatch(finished)).append('.');
        } else {
            out.append(teamName).append(" has no recent finished match listed.");
        }
        if (upcoming != null) {
            out.append(" Next match: ").append(describeUpcoming(upcoming)).append('.');
        }
        return out.toString();
    }

    private String standingFor(int teamId, String teamName, int leagueId) throws RetrievalException {
        JsonNode rows = standingsRows(leagueId);
        if (rows.isEmpty()) return "No standings available for " + teamName + ".";
        for (JsonNode row : rows) {
            String id = row.path("team").path("id").asText("");
            if (!id.isBlank() && Integer.parseInt(id) == teamId) {
                return teamName + " are " + ordinal(row.path("rank").asInt()) + " with "
                        + row.path("points").asInt() + " pts (" + rowText(row) + ").";
            }
        }
        return teamName + " not found in the current standings.";
    }

    private String standings(int leagueId) throws RetrievalException {
        JsonNode rows = standingsRows(leagueId);
        if (rows.isEmpty()) return "No standings available for that league right now.";
        String league = "Premier League";
        for (Map.Entry<String, Integer> e : LEAGUES.entrySet()) {
            if (e.getValue() == leagueId) league = e.getKey().toUpperCase(Locale.ROOT);
        }
        StringBuilder sb = new StringBuilder(league).append(" standings:\n");
        int top = Math.min(6, rows.size());
        for (int i = 0; i < top; i++) {
            JsonNode row = rows.get(i);
            sb.append(row.path("rank").asInt()).append(". ")
                    .append(row.path("team").path("name").asText("?")).append(" - ")
                    .append(row.path("points").asInt()).append(" pts (").append(rowText(row)).append(")\n");
        }
        return sb.toString().trim();
    }

    private JsonNode standingsRows(int leagueId) throws RetrievalException {
        int season = LocalDate.now().getMonthValue() >= 8 ? LocalDate.now().getYear() : LocalDate.now().getYear() - 1;
        JsonNode root = get("/standings?league=" + leagueId + "&season=" + season);
        JsonNode response = root.path("response");
        if (response.isArray() && !response.isEmpty()) {
            JsonNode standings = response.get(0).path("league").path("standings");
            if (standings.isArray() && !standings.isEmpty()) return standings.get(0);
        }
        return json.createArrayNode();
    }

    private JsonNode get(String path) throws RetrievalException {
        JsonNode root;
        try {
            String body = HttpHelper.jsonGet(baseUrl + path,
                    Map.of("x-apisports-key", apiKey), java.time.Duration.ofSeconds(20));
            root = json.readTree(body);
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("Failed to parse football response.",
                    "That sports service returned something unexpected.", e);
        }
        JsonNode errors = root.path("errors");
        if (errors.isObject() && errors.size() > 0) {
            JsonNode rate = findRateLimit(errors);
            if (rate != null) {
                throw new RetrievalException("Rate limited by sports API",
                        "That sports service is limiting requests right now, so I couldn't check.", null);
            }
            throw new RetrievalException("Sports API error: " + errors.toString(),
                    "Live sports information could not be retrieved.", null);
        }
        return root;
    }

    private static JsonNode findRateLimit(JsonNode errors) {
        var it = errors.fields();
        while (it.hasNext()) {
            var e = it.next();
            String v = e.getValue().asText("");
            if (e.getKey().toLowerCase(Locale.ROOT).contains("rate") || v.toLowerCase(Locale.ROOT).contains("limit")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String describeMatch(JsonNode f) {
        String home = f.path("teams").path("home").path("name").asText("?");
        String away = f.path("teams").path("away").path("name").asText("?");
        int gh = f.path("goals").path("home").asInt(-1);
        int ga = f.path("goals").path("away").asInt(-1);
        String league = f.path("league").path("name").asText("");
        String date = dateText(f.path("fixture").path("date").asText(""));
        String result;
        if (gh < 0) {
            result = "played";
        } else if (gh > ga) {
            result = "won " + gh + "-" + ga;
        } else if (gh < ga) {
            result = "lost " + gh + "-" + ga;
        } else {
            result = "drew " + gh + "-" + ga;
        }
        StringBuilder sb = new StringBuilder().append(home).append(" vs ").append(away)
                .append(" (").append(result).append(')');
        if (league != null && !league.isBlank()) sb.append(" in ").append(league);
        if (!date.isBlank()) sb.append(" on ").append(date);
        return sb.toString();
    }

    private static String describeUpcoming(JsonNode f) {
        String home = f.path("teams").path("home").path("name").asText("?");
        String away = f.path("teams").path("away").path("name").asText("?");
        String league = f.path("league").path("name").asText("");
        String date = dateText(f.path("fixture").path("date").asText(""));
        StringBuilder sb = new StringBuilder().append(home).append(" vs ").append(away);
        if (league != null && !league.isBlank()) sb.append(" (").append(league).append(')');
        if (!date.isBlank()) sb.append(" on ").append(date);
        return sb.toString();
    }

    private static String dateText(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            return OffsetDateTime.parse(iso).toLocalDate().format(DATE);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static boolean isFinished(String status) {
        return switch (status) {
            case "FT", "AET", "PEN" -> true;
            default -> false;
        };
    }

    private static boolean isUpcoming(String status) {
        return switch (status) {
            case "NS", "TBD", "PST", "SUSP", "CANC" -> true;
            default -> false;
        };
    }

    private static String ordinal(int rank) {
        int mod100 = rank % 100;
        if (mod100 >= 11 && mod100 <= 13) return rank + "th";
        return switch (rank % 10) {
            case 1 -> rank + "st";
            case 2 -> rank + "nd";
            case 3 -> rank + "rd";
            default -> rank + "th";
        };
    }

    private static String rowText(JsonNode row) {
        JsonNode all = row.path("all");
        return "P" + all.path("played").asInt()
                + " W" + all.path("win").asInt()
                + " D" + all.path("draw").asInt()
                + " L" + all.path("lose").asInt()
                + " GD " + row.path("goalsDiff").asInt();
    }

    private static boolean isLeagueQuery(String s) {
        for (String key : LEAGUES.keySet()) {
            if (s.contains(key)) return true;
        }
        return s.contains("table") || s.contains("standing") || s.contains("position");
    }

    private static int leagueId(String s) {
        for (Map.Entry<String, Integer> e : LEAGUES.entrySet()) {
            if (s.contains(e.getKey())) return e.getValue();
        }
        return 39;
    }

    private static String guessTeam(String s) {
        for (String t : TEAMS) {
            if (s.contains(t)) return switch (t) {
                case "man utd", "man united", "manchester united" -> "Manchester United";
                case "man city", "manchester city" -> "Manchester City";
                case "spurs" -> "Tottenham";
                case "wolves" -> "Wolverhampton";
                case "aston villa" -> "Aston Villa";
                case "west ham" -> "West Ham";
                case "crystal palace" -> "Crystal Palace";
                case "nottingham forest" -> "Nottingham Forest";
                default -> t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
            };
        }
        return null;
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record TeamInfo(int id, String name) {
    }
}