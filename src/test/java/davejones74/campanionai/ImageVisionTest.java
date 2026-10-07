package davejones74.campanionai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import davejones74.campanionai.llm.StubLlmServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for image attachments: upload validation, separate
 * persistence, the VISION→MAIN pipeline, role resolution through configuration,
 * and the failure paths. Provider behaviour is faked with {@link StubLlmServer}
 * so the tests exercise CompanionAI's own wiring only.
 */
class ImageVisionTest {

    private static final byte[] PNG_1PX = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    private final List<String> props = new ArrayList<>();
    private StubLlmServer llm;
    private HttpServer search;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        llm = new StubLlmServer();
        llm.chatJson("{\"choices\":[{\"message\":{\"content\":\"Here is what I found.\"},\"finish_reason\":\"stop\"}]}");

        search = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        search.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        search.start();

        set("companionai.llm.provider", "ollama");
        set("companionai.llm.baseUrl", llm.url());
        set("companionai.llm.model", "stub-main-model");
        set("campanionai.live.enabled", "false");
        set("campanionai.searchApiKey", "");
        set("campanionai.allowPrivateFetch", "true");
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
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void set(String key, String value) {
        System.setProperty(key, value);
        props.add(key);
    }

    private ModelServlet servlet() throws Exception {
        Path data = Files.createTempDirectory("companionai-image-test");
        dataDir = data;
        set("campanionai.dataDir", data.toString());
        ModelServlet servlet = new ModelServlet();
        servlet.init();
        return servlet;
    }

    // ---- upload validation ------------------------------------------------

    @Test
    void rejectsANonImageUpload() throws Exception {
        ModelServlet servlet = servlet();
        Response out = postMultipart(servlet, "/api/chat/image", "image", "notes.txt",
                "this is not an image".getBytes(StandardCharsets.UTF_8), "chat-1");

        assertEquals(400, out.status, out.body);
        assertTrue(out.body.contains("Unsupported or malformed"), out.body);
    }

    @Test
    void rejectsAnUnsupportedImageFormat() throws Exception {
        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0};
        Response out = postMultipart(servlet(), "/api/chat/image", "image", "a.gif", gif, "chat-1");

        assertEquals(400, out.status, out.body);
    }

    @Test
    void rejectsAnOversizedImage() throws Exception {
        byte[] big = new byte[(int) (10L * 1024 * 1024 + 1)];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        Response out = postMultipart(servlet(), "/api/chat/image", "image", "big.jpg", big, "chat-1");

        assertEquals(400, out.status, out.body);
        assertTrue(out.body.contains("too large"), out.body);
    }

    @Test
    void acceptsAValidPngAndStoresItSeparately() throws Exception {
        ModelServlet servlet = servlet();
        Response out = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");

        assertEquals(200, out.status, out.body);
        JsonNode saved = new ObjectMapper().readTree(out.body);
        String id = saved.path("id").asText();
        assertFalse(id.isBlank());
        assertEquals("image/png", saved.path("mimeType").asText());
        assertTrue(Files.exists(dataDir.resolve("images").resolve("chat-1").resolve(id + ".png")), out.body);
        String url = saved.path("url").asText();
        assertTrue(url.startsWith("/api/images/chat-1/"), url);
    }

    @Test
    void servesAStoredImageAndBlocksTraversalAndSidecars() throws Exception {
        ModelServlet servlet = servlet();
        Response up = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");
        JsonNode saved = new ObjectMapper().readTree(up.body);
        String id = saved.path("id").asText();

        Response got = get(servlet, "/api/images/chat-1/" + id + ".png");
        assertEquals(200, got.status, got.body);
        assertTrue(got.body.contains("PNG") || got.rawBytes()[1] == 'P', "png bytes served");

        Response sidecar = get(servlet, "/api/images/chat-1/" + id + ".analysis.txt");
        assertEquals(404, sidecar.status, sidecar.body);
        Response traversal = get(servlet, "/api/images/../chat-1/" + id + ".png");
        assertEquals(404, traversal.status, traversal.body);
    }

    @Test
    void doesNotInvokeVisionForATextOnlyMessage() throws Exception {
        ModelServlet servlet = servlet();
        llm.chatSse("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}", "[DONE]");

        Response out = post(servlet, "/api/chat/stream", "{\"message\":\"hello\"}");

        assertEquals(200, out.status, out.body);
        assertEquals(1, llm.requests(), "one main request only");
        for (String body : llm.allBodies()) {
            assertFalse(body.contains("image_url"), body);
        }
    }

    @Test
    void imageMessageRunsVisionFirstThenMainWithoutBase64InChatHistory() throws Exception {
        ModelServlet servlet = servlet();
        llm.chatSequence("{\"choices\":[{\"message\":{\"content\":\"A red square logo on white.\"},\"finish_reason\":\"stop\"}]}");
        llm.chatStreamSse("{\"choices\":[{\"delta\":{\"content\":\"That looks like a logo.\"}}]}", "[DONE]");
        Response up = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");
        JsonNode saved = new ObjectMapper().readTree(up.body);

        Response out = post(servlet, "/api/chat/stream",
                "{\"message\":\"What is this?\",\"chatId\":\"chat-1\",\"imageId\":\"" + saved.path("id").asText() + "\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Analysing image"), out.body);
        assertTrue(out.body.contains("That looks like a logo."), out.body);

        java.util.List<String> bodies = llm.allBodies();
        assertEquals(2, bodies.size(), bodies.toString());
        String visionReq = bodies.get(0);
        assertTrue(visionReq.contains("image_url"), visionReq);
        assertTrue(visionReq.contains("data:image/png;base64,"), visionReq);
        assertTrue(visionReq.contains("\"model\":\"qwen3vl-flash:4b\""), visionReq);
        assertTrue(visionReq.contains("visual perception component"), visionReq);

        String mainReq = bodies.get(1);
        assertTrue(mainReq.contains("Image analysis:"), mainReq);
        assertTrue(mainReq.contains("A red square logo on white."), mainReq);
        assertTrue(mainReq.contains("\"model\":\"stub-main-model\""), mainReq);
        assertFalse(mainReq.contains("base64"), "main model gets text only");

        // Sidecar written as derived metadata, distinguishable from history.
        Path dir = dataDir.resolve("images").resolve("chat-1");
        assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().endsWith(".analysis.txt")),
                "analysis sidecar written");

        // Chat history holds the image reference, never base64.
        davejones74.campanionai.chat.ChatStore cs = new davejones74.campanionai.chat.ChatStore(dataDir);
        java.util.List<davejones74.campanionai.chat.ChatMessage> msgs = cs.loadMessages("chat-1");
        assertEquals(2, msgs.size(), out.body);
        assertEquals("user", msgs.get(0).role());
        assertTrue(msgs.get(0).image() != null && msgs.get(0).image().id().equals(saved.path("id").asText()));
        String raw = Files.readString(dataDir.resolve("chats").resolve("chat-1_messages.json"));
        assertFalse(raw.contains("base64"), raw);
        assertFalse(raw.contains("A red square logo on white."),
                "raw vision output must not be written into the conversation as a message");
    }

    @Test
    void visionFailureReportsCleanlyAndDoesNotCallMain() throws Exception {
        ModelServlet servlet = servlet();
        llm.chatFailure(500, "{\"error\":{\"message\":\"vision exploded\"}}");
        Response up = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");
        JsonNode saved = new ObjectMapper().readTree(up.body);

        Response out = post(servlet, "/api/chat/stream",
                "{\"message\":\"What is this?\",\"chatId\":\"chat-1\",\"imageId\":\"" + saved.path("id").asText() + "\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("Image analysis failed"), out.body);
        assertEquals(1, llm.requests(), "main model never called");

        // User message persisted despite the failure; no assistant reply.
        davejones74.campanionai.chat.ChatStore cs = new davejones74.campanionai.chat.ChatStore(dataDir);
        java.util.List<davejones74.campanionai.chat.ChatMessage> msgs = cs.loadMessages("chat-1");
        assertEquals(1, msgs.size(), out.body);
        assertEquals("user", msgs.get(0).role());
    }

    @Test
    void mainFailureAfterSuccessfulVisionStillPersistsTheUserMessage() throws Exception {
        ModelServlet servlet = servlet();
        llm.chatSequence("{\"choices\":[{\"message\":{\"content\":\"An office photo.\"},\"finish_reason\":\"stop\"}]}");
        llm.chatSse("{\"error\":{\"message\":\"main exploded\"}}");
        Response up = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");
        JsonNode saved = new ObjectMapper().readTree(up.body);

        Response out = post(servlet, "/api/chat/stream",
                "{\"message\":\"What is this?\",\"chatId\":\"chat-1\",\"imageId\":\"" + saved.path("id").asText() + "\"}");

        assertEquals(200, out.status, out.body);
        assertTrue(out.body.contains("\"offline\":true"), out.body);

        // User message + image reference must persist despite the main-model failure,
        // even though the existing offline-fallback will also have persisted a reply.
        davejones74.campanionai.chat.ChatStore cs = new davejones74.campanionai.chat.ChatStore(dataDir);
        java.util.List<davejones74.campanionai.chat.ChatMessage> msgs = cs.loadMessages("chat-1");
        assertFalse(msgs.isEmpty(), out.body);
        assertEquals("user", msgs.get(0).role());
        assertTrue(msgs.get(0).image() != null);
    }

    @Test
    void imageReferenceSurvivesChatReload() throws Exception {
        ModelServlet servlet = servlet();
        llm.chatSequence("{\"choices\":[{\"message\":{\"content\":\"Observations.\"},\"finish_reason\":\"stop\"}]}");
        llm.chatStreamSse("{\"choices\":[{\"delta\":{\"content\":\"Answer.\"}}]}", "[DONE]");
        Response up = postMultipart(servlet, "/api/chat/image", "image", "photo.png", PNG_1PX, "chat-1");
        JsonNode saved = new ObjectMapper().readTree(up.body);
        post(servlet, "/api/chat/stream",
                "{\"message\":\"What is this?\",\"chatId\":\"chat-1\",\"imageId\":\"" + saved.path("id").asText() + "\"}");

        davejones74.campanionai.chat.ChatStore cs = new davejones74.campanionai.chat.ChatStore(dataDir);
        java.util.List<davejones74.campanionai.chat.ChatMessage> msgs = cs.loadMessages("chat-1");
        assertEquals(2, msgs.size());
        assertEquals(saved.path("id").asText(), msgs.get(0).image().id());
        assertEquals("/api/images/chat-1/" + saved.path("id").asText() + ".png", msgs.get(0).image().url());
        assertTrue(Files.exists(dataDir.resolve("images").resolve("chat-1").resolve(saved.path("id").asText() + ".png")));
    }

    // ---- servlet plumbing ----------------------------------------------------------

    private record Response(int status, String body, String error, byte[] rawBody) {
        Response(int status, String body, String error) {
            this(status, body, error, new byte[0]);
        }

        byte[] rawBytes() {
            return rawBody;
        }
    }

    private static Response post(ModelServlet servlet, String path, String body) throws Exception {
        HttpServletRequest req = request(path, body, null, null, "application/json");
        Capture capture = new Capture();
        HttpServletResponse resp = response(capture);
        servlet.service(req, resp);
        return new Response(capture.status, capture.body(), capture.error);
    }

    private static Response get(ModelServlet servlet, String path) throws Exception {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getRequestURI", path);
        answers.put("getMethod", "GET");
        answers.put("getContentType", "text/plain");
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                ImageVisionTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> {
                    Object answer = answers.get(m.getName());
                    return answer != null ? answer : defaultValue(m);
                });
        Capture capture = new Capture();
        servlet.service(req, response(capture));
        return new Response(capture.status, capture.body(), capture.error, capture.bytes());
    }

    private static Response postMultipart(ModelServlet servlet, String path, String partName, String filename,
                                          byte[] content, String chatId) throws Exception {
        HttpServletRequest req = request(path, "", Map.of(partName, new FakePart(filename, content)),
                Map.of("chatId", chatId), "multipart/form-data; boundary=x");
        Capture capture = new Capture();
        servlet.service(req, response(capture));
        return new Response(capture.status, capture.body(), capture.error);
    }

    private static class FakePart implements Part {
        private final String filename;
        private final byte[] bytes;

        FakePart(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }

        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public long getSize() { return bytes.length; }
        @Override public String getSubmittedFileName() { return filename; }
        @Override public String getName() { return "part"; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public void write(String fileName) {}
        @Override public void delete() {}
        @Override public String getHeader(String name) { return null; }
        @Override public java.util.Collection<String> getHeaders(String name) { return java.util.List.of(); }
        @Override public java.util.Collection<String> getHeaderNames() { return java.util.List.of(); }
    }

    private static HttpServletRequest request(String path, String body, Map<String, Part> parts,
                                              Map<String, String> params, String contentType) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getRequestURI", path);
        answers.put("getMethod", "POST");
        answers.put("getRequestURL", "http://localhost" + path);
        answers.put("getQueryString", null);
        answers.put("getRemoteAddr", "127.0.0.1");
        answers.put("getHeader", "");
        answers.put("getContentType", contentType);
        answers.put("getSession", null);
        answers.put("getServletContext", null);
        answers.put("getCharacterEncoding", "UTF-8");
        answers.put("getInputStream", new jakarta.servlet.ServletInputStream() {
            private final java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8));

            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
        });
        return (HttpServletRequest) Proxy.newProxyInstance(
                ImageVisionTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getPart":
                            return parts == null ? null : parts.get(a[0]);
                        case "getParameter":
                            return params == null ? "" : params.getOrDefault(a[0], "");
                        default:
                            Object answer = answers.get(m.getName());
                            return answer != null ? answer : defaultValue(m);
                    }
                });
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
                    return new jakarta.servlet.ServletOutputStream() {
                        @Override public boolean isReady() { return true; }
                        @Override public void setWriteListener(jakarta.servlet.WriteListener listener) { }
                        @Override public void write(int b) throws java.io.IOException { capture.buffer.write(b); }
                        @Override public void write(byte[] b, int off, int len) { capture.buffer.write(b, off, len); }
                    };
                default:
                    return defaultValue(m);
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                ImageVisionTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, h);
    }

    private static Object defaultValue(Method m) {
        Class<?> type = m.getReturnType();
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
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

        byte[] bytes() {
            writer().flush();
            return buffer.toByteArray();
        }
    }
}
