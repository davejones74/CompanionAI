package davejones74.campanionai.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SportsProviderTest {

    @Test
    void teamFixturesAndStandings() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/teams", "{\"response\":[{\"team\":{\"id\":42,\"name\":\"Arsenal\"}}]}");
            s.on("/fixtures", fixturesBody());
            s.on("/standings", standingsBody());
            SportsProvider p = new SportsProvider("key", 100, s.url());

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "How did Arsenal do and where are they in the table?",
                    RetrievalKind.SPORTS, null, Freshness.TODAY, 0, "Arsenal"));

            String content = r.items().get(0).content();
            assertTrue(content.contains("last match"), content);
            assertTrue(content.contains("won 2-1"), content);
            assertTrue(content.contains("Next match"), content);
            assertTrue(content.contains("are 2nd with 27 pts"), content);
        }
    }

    @Test
    void leagueStandingsOnly() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/standings", standingsBody());
            SportsProvider p = new SportsProvider("key", 100, s.url());

            RetrievalResult r = p.retrieve(new RetrievalRequest(
                    "Who is top of the Premier League standings?",
                    RetrievalKind.SPORTS, null, Freshness.TODAY, 0, null));

            String content = r.items().get(0).content();
            assertTrue(content.contains("PREMIER LEAGUE standings"), content);
            assertFalse(content.contains("last match"), content);
        }
    }

    @Test
    void rateLimitIsFriendly() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/teams", "{\"errors\":{\"requests\":\"limit reached\"},\"response\":[]}");
            SportsProvider p = new SportsProvider("key", 100, s.url());

            RetrievalException e = assertThrows(RetrievalException.class, () -> p.retrieve(
                    new RetrievalRequest("How did Arsenal do?", RetrievalKind.SPORTS, null, Freshness.TODAY, 0, "Arsenal")));
            assertTrue(e.userFacingMessage().contains("limiting"), e.userFacingMessage());
        }
    }

    @Test
    void unknownTeamIsFriendly() throws Exception {
        try (StubServer s = new StubServer()) {
            s.on("/teams", "{\"response\":[]}");
            SportsProvider p = new SportsProvider("key", 100, s.url());

            RetrievalException e = assertThrows(RetrievalException.class, () -> p.retrieve(
                    new RetrievalRequest("How did Marxingly United do?", RetrievalKind.SPORTS, null, Freshness.TODAY, 0, "Marxingly United")));
            assertTrue(e.userFacingMessage().contains("team"), e.userFacingMessage());
        }
    }

    private static String fixturesBody() {
        return """
                {"response":[
                  {"fixture":{"status":{"short":"FT"},"date":"2026-09-20T15:00:00+00:00"},
                   "teams":{"home":{"name":"Arsenal"},"away":{"name":"Chelsea"}},
                   "goals":{"home":2,"away":1},"league":{"name":"Premier League"}},
                  {"fixture":{"status":{"short":"NS"},"date":"2026-09-27T17:30:00+00:00"},
                   "teams":{"home":{"name":"Manchester United"},"away":{"name":"Arsenal"}},
                   "goals":{"home":null,"away":null},"league":{"name":"Premier League"}}
                ]}""";
    }

    private static String standingsBody() {
        return """
                {"response":[{"league":{"standings":[[
                  {"rank":1,"team":{"id":9,"name":"Liverpool"},"points":33,"goalsDiff":21,
                   "all":{"played":11,"win":10,"draw":1,"lose":0}},
                  {"rank":2,"team":{"id":42,"name":"Arsenal"},"points":27,"goalsDiff":18,
                   "all":{"played":11,"win":8,"draw":3,"lose":0}},
                  {"rank":3,"team":{"id":50,"name":"Manchester City"},"points":26,"goalsDiff":14,
                   "all":{"played":11,"win":8,"draw":2,"lose":1}}
                ]]}}]}""";
    }
}