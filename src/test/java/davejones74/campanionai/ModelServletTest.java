package davejones74.campanionai;

import com.sun.net.httpserver.HttpServer;
import davejones74.campanionai.llm.StubLlmServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behaviour of {@code /api/chat} and {@code /api/chat/stream}.
 *
 * <p>These are the two tests that guard the cost of a request. {@code /api/chat} used to build
 * its live context twice, so every non-streaming chat paid for the search twice; and the search
 * itself is now three queries for a research request. A regression in either shows up here as an
 * unexpected hit count rather than as a subtly worse answer, which is what makes it worth
 * asserting exactly.
 */
class ModelServletTest {

    private final List<String> props = new ArrayList<>();
    private StubLlmServer llm;
    private HttpServer search;
    private final AtomicInteger searchHits = new AtomicInteger();
    private final AtomicInteger articleHits = new AtomicInteger();
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        llm = new StubLlmServer();
        llm.chatJson("{\"choices\":[{\"message\":{\"content\":\"Here is what I found.\"},\"finish_reason\":\"stop\"}]}");

        search = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        search.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().equals("/search")) {
                searchHits.incrementAndGet();
            } else {
                articleHits.incrementAndGet();
            }
            byte[] body = ("{\"results\":[{\"title\":\"A story\",\"url\":\"https://www.bbc.co.uk/news/1\","
                    + "\"domain\":\"www.bbc.co.uk\",\"content\":\"A snippet about the subject.\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        search.start();

        set("companionai.llm.provider", "ollama");
        set("companionai.llm.baseUrl", llm.url());
        set("campanionai.llm.model", "stub-model");
        set("campanionai.live.enabled", "true");
        set("campanionai.live.intent", "rule");
        set("campanionai.searchApiKey", "test-search-key");
        set("campanionai.live.web.searchUrl", search.getAddress().getPort() == 0
                ? "" : "http://127.0.0.1:" + search.getAddress().getPort() + "/search");
        set("campanionai.allowPrivateFetch", "true");
        // No page fetching: the result URLs are real hostnames and must not be dereferenced.
        set("campanionai.live.fetchPages", "0");
        set("campanionai.live.web.researchFetchPages", "0");
    }

    @AfterEach
    void tearDown() {
        for (String key : props) {
            System.clearProperty(key);
        }
        props.clear();
        search.stop(0);
        llm.close();
        deleteTree(dataDir);
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A locked temp file must not fail the test.
                }
            });
        } catch (IOException ignored) {
            // Best effort: the OS will clear its temp directory anyway.
        }
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        props.add(key);
    }

private ModelServlet servlet() throws Exception {
        // init() is what binds the LLM provider and the retrieval stack from configuration, so it
        // is the only way to exercise the request path. The data directory is redirected to a temp
        // folder so a chat store is not written into the working tree.
        Path data = Files.createTempDirectory("companionai-servlet-test");
        dataDir = data;
        set("campanionai.dataDir", data.toString());
        ModelServlet servlet = new ModelServlet();
        servlet.init();
        return servlet;
    }

    @Test
    void chatRunsEachPlannedSearchOnce() throws Exception {
        Response out = post(servlet(), "/api/chat", "{\"message\":\"bbc.co.uk - give me all the stories about Huw Edwards\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Here is what I found."), out.body);
        assertEquals(3, searchHits.get(),
                "a research request is three searches, not three searches twice over");
        assertTrue(out.body.contains("\"sources\""), out.body);
    }

    @Test
    void chatReturnsASingleSearchForAPlainQuestion() throws Exception {
        Response out = post(servlet(), "/api/chat", "{\"message\":\"what is the latest news on AI models?\"}");

        assertEquals(200, out.status, out.body);
        assertEquals(1, searchHits.get());
    }

    @Test
    void chatDoesNotSearchWhenTheQuestionNeedsNoLiveData() throws Exception {
        Response out = post(servlet(), "/api/chat", "{\"message\":\"hello there\"}");

        assertEquals(200, out.status, out.body);
        assertEquals(0, searchHits.get());
    }

    @Test
    void aUrlInTheMessageIsFetchedRatherThanSearchedFor() throws Exception {
        // A scheme-qualified URL is a page to fetch. It must not become a search scope, and it
        // must not be turned into a query, which is the existing behaviour this must preserve.
        Response out = post(servlet(), "/api/chat",
                "{\"message\":\"what does this say? " + searchUrl("/article") + "\"}");

        assertEquals(200, out.status, out.body);
        assertEquals(0, searchHits.get(), "a full URL is not a search scope");
        assertEquals(1, articleHits.get(), "the page itself is fetched directly");
        assertTrue(out.body.contains("Here is what I found."), out.body);
    }

    @Test
    void streamReportsScopeThenCountsBeforeTheReply() throws Exception {
        Response out = post(servlet(), "/api/chat/stream",
                "{\"message\":\"bbc.co.uk - give me all the stories about Huw Edwards\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("\"status\":\"Searching bbc.co.uk...\""), out.body);
        assertTrue(out.body.contains("Found 1 matching page across 3 searches"),
                out.body);
        assertTrue(out.body.indexOf("Searching bbc.co.uk...")
                < out.body.indexOf("Here is what I found."), out.body);
    }

    @Test
    void streamCompletesNormallyWithoutInterruptedOrTruncatedFlags() throws Exception {
        llm.chatSse("{\"choices\":[{\"delta\":{\"content\":\"Hi there\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", "[DONE]");

        Response out = post(servlet(), "/api/chat/stream", "{\"message\":\"hello there\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Hi there"), out.body);
        assertTrue(out.body.contains("\"interrupted\":false"), out.body);
        assertTrue(out.body.contains("\"truncated\":false"), out.body);
    }

    @Test
    void streamReachingTheTokenLimitIsReportedAsTruncatedNotFailed() throws Exception {
        llm.chatSse("{\"choices\":[{\"delta\":{\"content\":\"Partial answer\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],"
                        + "\"usage\":{\"completion_tokens\":1024}}", "[DONE]");

        Response out = post(servlet(), "/api/chat/stream", "{\"message\":\"hello there\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Partial answer"), out.body);
        assertTrue(out.body.contains("\"truncated\":true"), out.body);
        assertTrue(out.body.contains("\"interrupted\":false"), out.body);
        assertFalse(out.body.contains("\"error\""), out.body);
    }

    @Test
    void anInterruptedStreamKeepsThePartialReplyAndDoesNotRetry() throws Exception {
        llm.chatSse("{\"choices\":[{\"delta\":{\"content\":\"Partial answer\"}}]}",
                "{\"error\":{\"message\":\"connection reset\"}}");
        ModelServlet servlet = servlet();
        int requestsBefore = llm.requests();

        Response out = post(servlet, "/api/chat/stream", "{\"message\":\"hello there\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Partial answer"),
                "the partial reply must be kept, not replaced by a generic error: " + out.body);
        assertTrue(out.body.contains("\"interrupted\":true"), out.body);
        assertFalse(out.body.contains("\"error\""), out.body);
        assertEquals(1, llm.requests() - requestsBefore,
                "no second LLM request may be made once content has streamed");
    }

    private static String toolCallReply(String... calls) {
        StringBuilder sb = new StringBuilder("{\"choices\":[{\"message\":{\"tool_calls\":[");
        for (int i = 0; i < calls.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"c").append(i).append("\",\"type\":\"function\",")
                    .append("\"function\":{\"name\":\"create_file\",\"arguments\":\"").append(calls[i]).append("\"}}");
        }
        return sb.append("]}}]}").toString();
    }

    private static String createFileArgs(String filename, String content) {
        return "{\\\"filename\\\":\\\"" + filename + "\\\",\\\"mimeType\\\":\\\"text/plain\\\","
                + "\\\"content\\\":\\\"" + content + "\\\"}";
    }

    @Test
    void streamEmitsTheGeneratedFileOnceWithItsDownloadInfo() throws Exception {
        llm.chatJson(toolCallReply(createFileArgs("cities.txt", "London, York")));
        llm.chatStreamSse("{\"choices\":[{\"delta\":{\"content\":\"Here is your file.\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", "[DONE]");
        ModelServlet servlet = servlet();
        int requestsBefore = llm.requests();

        Response out = post(servlet, "/api/chat/stream",
                "{\"message\":\"create a text file listing cities in England\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("cities.txt")),
                "create_file must still write the physical file");
        assertTrue(out.body.contains("Here is your file."), out.body);
        assertTrue(out.body.contains("\"files\":[{"), out.body);
        assertTrue(out.body.contains("\"name\":\"cities.txt\""), out.body);
        assertTrue(out.body.contains("\"url\":\"/api/files/cities.txt\""), out.body);
        assertTrue(out.body.contains("\"mimeType\":\"text/plain\""), out.body);
        assertEquals(out.body.indexOf("\"files\""), out.body.lastIndexOf("\"files\""),
                "the files event must be emitted exactly once: " + out.body);
        assertEquals(2, llm.requests() - requestsBefore,
                "one tool-planning request plus one streaming request");
    }

    @Test
    void streamEmitsEveryGeneratedFile() throws Exception {
        llm.chatJson(toolCallReply(
                createFileArgs("cities.txt", "London"),
                createFileArgs("towns.csv", "Windsor"),
                createFileArgs("notes.md", "# Notes")));
        llm.chatStreamSse("{\"choices\":[{\"delta\":{\"content\":\"Three files created.\"}}]}",
                "[DONE]");

        Response out = post(servlet(), "/api/chat/stream",
                "{\"message\":\"create a text file, a csv file and a markdown file\"}");

        assertEquals(200, out.status, out.body);
        for (String name : List.of("cities.txt", "towns.csv", "notes.md")) {
            assertTrue(Files.exists(dataDir.resolve("generated").resolve(name)), name);
            assertTrue(out.body.contains("\"name\":\"" + name + "\""), out.body);
        }
    }

    @Test
    void aNormalStreamWithoutFilesEmitsNoFilesEvent() throws Exception {
        llm.chatSse("{\"choices\":[{\"delta\":{\"content\":\"Just a reply.\"}}]}", "[DONE]");

        Response out = post(servlet(), "/api/chat/stream", "{\"message\":\"hello there\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Just a reply."), out.body);
        assertFalse(out.body.contains("\"files\""), out.body);
    }

    private String searchUrl(String path) {
        return "http://127.0.0.1:" + search.getAddress().getPort() + path;
    }

    // ---- servlet plumbing ----------------------------------------------------------

    private record Response(int status, String body, String error) {
    }

    private static Response post(ModelServlet servlet, String path, String body) throws Exception {
        HttpServletRequest req = request(path, body);
        Capture capture = new Capture();
        HttpServletResponse resp = response(capture);
        servlet.service(req, resp);
        return new Response(capture.status, capture.body(), capture.error);
    }

    private static HttpServletRequest request(String path, String body) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getRequestURI", path);
        answers.put("getMethod", "POST");
        answers.put("getRequestURL", "http://localhost" + path);
        answers.put("getQueryString", null);
        answers.put("getRemoteAddr", "127.0.0.1");
        answers.put("getHeader", "");
        answers.put("getContentType", "application/json");
        answers.put("getParameter", "");
        answers.put("getSession", null);
        answers.put("getServletContext", null);
        answers.put("getCharacterEncoding", "UTF-8");
        answers.put("getInputStream", new jakarta.servlet.ServletInputStream() {
            private final java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8));

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {
            }
        });
        return (HttpServletRequest) Proxy.newProxyInstance(
                ModelServletTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> answer(answers, m, a));
    }

    private static HttpServletResponse response(Capture capture) {
        InvocationHandler h = (p, m, a) -> {
            switch (m.getName()) {
                case "setCharacterEncoding", "setContentType", "setHeader", "setContentLength",
                        "flushBuffer":
                    return null;
                case "sendError":
                    capture.error = String.valueOf(a != null && a.length > 1 ? a[1] : "");
                    if (a != null && a.length > 0 && a[0] instanceof Integer code) {
                        capture.status = code;
                    }
                    return null;
                case "setStatus":
                    capture.status = (Integer) a[0];
                    return null;
                case "getWriter":
                    return capture.writer();
                case "getOutputStream":
                    return new java.io.BufferedOutputStream(capture.buffer);
                default:
                    return defaultValue(m);
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                ModelServletTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, h);
    }

    /** Routes the small set of methods the servlet actually calls on the two interfaces. */
    private static Object answer(Map<String, Object> answers, Method m, Object[] args) {
        Object answer = answers.get(m.getName());
        return answer != null ? answer : defaultValue(m);
    }

    private static Object defaultValue(Method m) {
        Class<?> type = m.getReturnType();
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static final class Capture {
        int status = 200;
        String error = "";
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private PrintWriter writer;

        PrintWriter writer() {
            if (writer == null) {
                writer = new PrintWriter(new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
            }
            return writer;
        }

        String body() {
            writer().flush();
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

}



