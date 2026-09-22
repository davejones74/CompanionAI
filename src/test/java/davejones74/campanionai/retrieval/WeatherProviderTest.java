package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherProviderTest {

    @Test
    void retrievesCurrentWeather() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/v1/search", "{\"results\":[{\"name\":\"Uxbridge\",\"latitude\":51.54,\"longitude\":-0.47}]}");
            s.on("/v1/forecast", forecastBody());
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "What is the weather in Uxbridge?", RetrievalKind.WEATHER, "Uxbridge", Freshness.TODAY, 0, null));

            assertEquals(1, r.items().size());
            String content = r.items().get(0).content();
            assertTrue(content.contains("Weather for Uxbridge"), content);
            assertTrue(content.contains("partly cloudy"), content);
            assertTrue(content.contains("max 20"), content);
        }
    }

    @Test
    void retrievesYesterdayArchive() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/v1/search", "{\"results\":[{\"name\":\"London\",\"latitude\":51.5,\"longitude\":-0.12}]}");
            s.on("/v1/archive", "{\"daily\":{\"time\":[\"2026-09-21\"],\"weather_code\":[61],"
                    + "\"temperature_2m_max\":[19.2],\"temperature_2m_min\":[11.4],"
                    + "\"precipitation_sum\":[2.3],\"wind_speed_10m_max\":[14.0]}}");
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "What was the weather yesterday?", RetrievalKind.WEATHER, "London", Freshness.ANY, 1, null));

            assertFalse(r.items().isEmpty());
            String content = r.items().get(0).content();
            assertTrue(content.contains("rain"), content);
            assertTrue(content.contains("19"), content);
        }
    }

    @Test
    void rejectsMissingLocation() throws Exception {
        try (StubServer s = new StubServer()) {
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");
            RetrievalException e = assertThrows(RetrievalException.class, () -> p.retrieve(
                    new RetrievalRequest("weather?", RetrievalKind.WEATHER, null, Freshness.TODAY, 0, null)));
            assertTrue(e.userFacingMessage().contains("location"));
        }
    }

    @Test
    void archiveRequestIncludesWeatherCode() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/v1/search", "{\"results\":[{\"name\":\"London\",\"latitude\":51.5,\"longitude\":-0.12}]}");
            s.on("/v1/archive", "{\"daily\":{\"time\":[\"2026-09-21\"],\"weather_code\":[61],"
                    + "\"temperature_2m_max\":[19.2],\"temperature_2m_min\":[11.4],"
                    + "\"precipitation_sum\":[2.3],\"wind_speed_10m_max\":[14.0]}}");
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");

            p.retrieve(new RetrievalRequest(
                    "What was the weather yesterday?", RetrievalKind.WEATHER, "London", Freshness.ANY, 1, null));

            String q = s.query("/v1/archive");
            assertTrue(q.contains("weather_code"), "archive request must ask for weather_code: " + q);
        }
    }

    @Test
    void dailyLabelsDeriveFromProviderDatesInsteadOfLocalNow() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/v1/search", "{\"results\":[{\"name\":\"London\",\"latitude\":51.5,\"longitude\":-0.12}]}");
            s.on("/v1/forecast", forecastBodyWithDates("[\"2026-01-01\",\"2026-01-02\",\"2026-01-03\"]"));
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "What's the weather in London?", RetrievalKind.WEATHER, "London", Freshness.TODAY, 0, null));

            String content = r.items().get(0).content();
            assertTrue(content.contains("Today"), content);
            assertTrue(content.contains("Tomorrow"), content);
            assertTrue(content.contains("2026-01-03"),
                    "labels must use the provider's dates, not LocalDate.now().plusDays(i): " + content);
        }
    }

    @Test
    void readsDailyMinAndMaxArrays() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/v1/search", "{\"results\":[{\"name\":\"London\",\"latitude\":51.5,\"longitude\":-0.12}]}");
            s.on("/v1/forecast", forecastBodyWithTemps());
            WeatherProvider p = new WeatherProvider(s.url() + "/v1/search", s.url() + "/v1/forecast", s.url() + "/v1/archive");

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "Weather in London?", RetrievalKind.WEATHER, "London", Freshness.TODAY, 0, null));

            String content = r.items().get(0).content();
            assertTrue(content.contains("max 21"), content);
            assertTrue(content.contains("min 8"), content);
        }
    }

    @Test
    void alwaysConfigured() {
        assertTrue(new WeatherProvider().isConfigured());
    }

    private static String forecastBodyWithDates(String dates) {
    return "{\"current\":{\"time\":\"2026-01-01T12:00\",\"temperature_2m\":10.0,"
            + "\"apparent_temperature\":9.0,\"relative_humidity_2m\":70,\"precipitation\":0.0,"
            + "\"weather_code\":0,\"wind_speed_10m\":5.0},"
            + "\"daily\":{\"time\":" + dates + ",\"weather_code\":[0,1,2],"
            + "\"temperature_2m_max\":[11.0,12.0,13.0],\"temperature_2m_min\":[3.0,4.0,5.0],"
            + "\"precipitation_probability_max\":[10,20,30],\"wind_speed_10m_max\":[6,7,8]},"
            + "\"hourly\":{\"time\":[],\"precipitation_probability\":[],\"temperature_2m\":[]}}";
}

    private static String forecastBodyWithTemps() {
        return """
                {"current":{"time":"2026-09-22T12:00","temperature_2m":20.0,"apparent_temperature":19.0,
                 "relative_humidity_2m":60,"precipitation":0.0,"weather_code":2,"wind_speed_10m":7.0},
                "daily":{"time":["2026-09-22","2026-09-23","2026-09-24"],"weather_code":[2,63,0],
                 "temperature_2m_max":[21.0,18.0,22.0],"temperature_2m_min":[8.0,9.0,10.0],
                 "precipitation_probability_max":[20,80,5],"wind_speed_10m_max":[9,10,12]},
                "hourly":{"time":[],"precipitation_probability":[],"temperature_2m":[]}}""";
    }

    private static String forecastBody() {
        return """
                {"current":{"time":"2026-09-22T12:00","temperature_2m":18.5,"apparent_temperature":17.0,
                 "relative_humidity_2m":60,"precipitation":0.1,"weather_code":2,"wind_speed_10m":12.5},
                "daily":{"time":["2026-09-22","2026-09-23","2026-09-24"],"weather_code":[2,61,0],
                 "temperature_2m_max":[20.2,18.1,22.0],"temperature_2m_min":[10.1,9.2,11.3],
                 "precipitation_probability_max":[20,80,5],"wind_speed_10m_max":[15,20,10]},
                "hourly":{"time":["2026-09-22T18:00","2026-09-22T19:00"],
                 "precipitation_probability":[60,70],"temperature_2m":[18.0,17.5]}}""";
    }
}