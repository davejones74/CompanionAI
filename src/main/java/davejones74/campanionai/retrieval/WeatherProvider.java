package davejones74.campanionai.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Weather via the free, keyless Open-Meteo APIs (geocoding, forecast and
 * archive). Location comes from the request, never hard-coded.
 */
public final class WeatherProvider implements RetrievalProvider {

    private static final String DEFAULT_GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String DEFAULT_FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String DEFAULT_ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final String geocodingUrl;
    private final String forecastUrl;
    private final String archiveUrl;
    private final ObjectMapper json = new ObjectMapper();

    public WeatherProvider() {
        this(DEFAULT_GEOCODING_URL, DEFAULT_FORECAST_URL, DEFAULT_ARCHIVE_URL);
    }

    WeatherProvider(String geocodingUrl, String forecastUrl, String archiveUrl) {
        this.geocodingUrl = geocodingUrl;
        this.forecastUrl = forecastUrl;
        this.archiveUrl = archiveUrl;
    }

    @Override
    public RetrievalKind kind() {
        return RetrievalKind.WEATHER;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public RetrievalResult retrieve(RetrievalRequest request) throws RetrievalException {
        String location = request.location();
        if (location == null || location.isBlank()) {
            throw new RetrievalException("No location given for weather request",
                    "I don't know which location to check the weather for.");
        }
        double[] latLon = geocode(location);
        String summary;
        String sourceUrl;
        if (request.pastDays() > 0) {
            summary = archive(latLon, location, request.pastDays());
            sourceUrl = archiveUrl;
        } else {
            summary = forecast(latLon, location);
            sourceUrl = forecastUrl;
        }
        RetrievalItem item = new RetrievalItem("Open-Meteo", "Weather for " + location, sourceUrl,
                null, null, summary.trim());
        return new RetrievalResult(RetrievalKind.WEATHER, Instant.now(), List.of(item));
    }

    private double[] geocode(String location) throws RetrievalException {
        String url = geocodingUrl + "?name=" + urlEncode(location) + "&count=1&language=en&format=json";
        try {
            JsonNode root = json.readTree(HttpHelper.jsonGet(url, java.time.Duration.ofSeconds(20)));
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new RetrievalException("Location not found: " + location,
                        "I couldn't find '" + location + "' to check the weather for.");
            }
            JsonNode first = results.get(0);
            double lat = first.path("latitude").asDouble();
            double lon = first.path("longitude").asDouble();
            if (lat == 0.0 && lon == 0.0) {
                throw new RetrievalException("Location not found: " + location,
                        "I couldn't find '" + location + "' to check the weather for.");
            }
            return new double[]{lat, lon};
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("Failed to parse weather geocoding response for " + location,
                    "Current weather information could not be retrieved.", e);
        }
    }

    private String forecast(double[] latLon, String location) throws RetrievalException {
        String url = forecastUrl
                + "?latitude=" + latLon[0] + "&longitude=" + latLon[1]
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
                + "&hourly=temperature_2m,precipitation_probability"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max"
                + "&timezone=auto&forecast_days=3";
        try {
            JsonNode root = json.readTree(HttpHelper.jsonGet(url, java.time.Duration.ofSeconds(20)));
            StringBuilder sb = new StringBuilder("Weather for ").append(location).append(':').append('\n');
            JsonNode current = root.path("current");
            if (current.isObject() && current.path("temperature_2m").isNumber()) {
                sb.append("Current (").append(timeText(current.path("time").asText("")))
                        .append("): ").append(deg(current.path("temperature_2m").asDouble()))
                        .append(", feels like ").append(deg(current.path("apparent_temperature").asDouble()))
                        .append(", ").append(describe(current.path("weather_code").asInt()))
                        .append(", wind ").append(kmh(current.path("wind_speed_10m").asDouble()))
                        .append(", humidity ").append(percent(current.path("relative_humidity_2m").asDouble()))
                        .append(", precipitation now ").append(mm(current.path("precipitation").asDouble()))
                        .append(".\n");
            }
            JsonNode daily = root.path("daily");
            if (daily.isObject() && daily.path("time").isArray()) {
                for (int i = 0; i < daily.path("time").size(); i++) {
                    sb.append(dayLabel(daily, i)).append(": ")
                            .append(describe(codeAt(daily.path("weather_code"), i)))
                            .append(", max ").append(deg(doubleAt(daily.path("temperature_2m_max"), i)))
                            .append(", min ").append(deg(doubleAt(daily.path("temperature_2m_min"), i)))
                            .append(", rain chance ").append(percent(doubleAt(daily.path("precipitation_probability_max"), i)))
                            .append(", wind ").append(kmh(doubleAt(daily.path("wind_speed_10m_max"), i)))
                            .append(".\n");
                }
            }
            appendEvening(root, sb);
            return sb.toString();
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("Failed to parse weather forecast response for " + location,
                    "Current weather information could not be retrieved.", e);
        }
    }

    private String archive(double[] latLon, String location, int pastDays) throws RetrievalException {
        LocalDate day = LocalDate.now().minusDays(Math.min(pastDays, 92));
        String url = archiveUrl
                + "?latitude=" + latLon[0] + "&longitude=" + latLon[1]
                + "&start_date=" + day + "&end_date=" + day
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum,wind_speed_10m_max"
                + "&timezone=auto";
        try {
            JsonNode root = json.readTree(HttpHelper.jsonGet(url, java.time.Duration.ofSeconds(20)));
            JsonNode daily = root.path("daily");
            StringBuilder sb = new StringBuilder("Weather for ").append(location).append(" on ")
                    .append(day).append(": ");
            if (daily.isObject() && daily.path("time").isArray() && !daily.path("time").isEmpty()) {
                sb.append(describe(codeAt(daily.path("weather_code"), 0)))
                        .append(", max ").append(deg(doubleAt(daily.path("temperature_2m_max"), 0)))
                        .append(", min ").append(deg(doubleAt(daily.path("temperature_2m_min"), 0)))
                        .append(", precipitation ").append(mm(doubleAt(daily.path("precipitation_sum"), 0)))
                        .append(", wind ").append(kmh(doubleAt(daily.path("wind_speed_10m_max"), 0)))
                        .append(".");
            } else {
                sb.append("no data available for that day.");
            }
            return sb.toString();
        } catch (RetrievalException e) {
            throw e;
        } catch (Exception e) {
            throw new RetrievalException("Failed to parse weather archive response for " + location,
                    "Current weather information could not be retrieved.", e);
        }
    }

    private void appendEvening(JsonNode root, StringBuilder sb) {
        JsonNode hourly = root.path("hourly");
        JsonNode times = hourly.isObject() ? hourly.path("time") : null;
        JsonNode probs = hourly.isObject() ? hourly.path("precipitation_probability") : null;
        JsonNode temps = hourly.isObject() ? hourly.path("temperature_2m") : null;
        if (times == null || !times.isArray() || times.isEmpty()) return;

        int maxIndex = -1;
        double maxProb = -1;
        double tempAtMax = 0;
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime ts = parseTime(times.get(i).asText());
            if (ts == null) continue;
            int hour = ts.getHour();
            if (hour >= 18 && hour <= 23) {
                double p = probs != null && probs.isArray() && i < probs.size() ? probs.get(i).asDouble() : 0;
                if (p > maxProb) {
                    maxProb = p;
                    maxIndex = i;
                    tempAtMax = temps != null && temps.isArray() && i < temps.size() ? temps.get(i).asDouble() : 0;
                }
            }
        }
        if (maxIndex >= 0) {
            sb.append("This evening: ").append(percent(maxProb)).append(" chance of rain, ~")
                    .append(deg(tempAtMax)).append(".\n");
        }
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, ISO_LOCAL);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String dayLabel(JsonNode daily, int i) {
        String raw = daily.path("time").isArray() && i < daily.path("time").size()
                ? daily.path("time").get(i).asText("") : "";
        LocalDate date = parseDate(raw);
        if (date == null) {
            return "Day " + (i + 1);
        }
        String baseRaw = daily.path("time").isArray() && !daily.path("time").isEmpty()
                ? daily.path("time").get(0).asText("") : "";
        LocalDate base = parseDate(baseRaw);
        if (base != null) {
            long offset = ChronoUnit.DAYS.between(base, date);
            if (offset == 0) return "Today";
            if (offset == 1) return "Tomorrow";
        }
        return date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + date;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String timeText(String s) {
        LocalDateTime ts = parseTime(s);
        return ts == null ? s : ts.format(TIME);
    }

    private static int codeAt(JsonNode arr, int i) {
        if (arr.isArray() && i < arr.size() && arr.get(i).isNumber()) return arr.get(i).asInt();
        return -1;
    }

    private static double doubleAt(JsonNode arr, int i) {
        if (arr.isArray() && i < arr.size() && arr.get(i).isNumber()) return arr.get(i).asDouble();
        return 0;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String deg(double v) {
        return Math.round(v) + "\u00B0C";
    }

    private static String kmh(double v) {
        return Math.round(v) + " km/h";
    }

    private static String percent(double v) {
        return Math.round(v) + "%";
    }

    private static String mm(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f mm", v);
    }

    private static String describe(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1 -> "mostly clear";
            case 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45, 48 -> "foggy";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 80, 81, 82 -> "rain";
            case 66, 67, 71, 73, 75, 77, 85, 86 -> "snow";
            case 95, 96, 99 -> "thunderstorm";
            default -> "cloudy or unsettled";
        };
    }
}