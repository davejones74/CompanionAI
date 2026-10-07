package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal OpenAI-compatible LLM endpoint for tests.
 *
 * <p>Unlike the older single-body stub used by the retrieval provider tests,
 * this one can stream a response line by line, return a chosen HTTP status, and
 * capture the request body. That is what makes it possible to assert the request
 * the providers generate — {@code stream}, {@code temperature},
 * {@code options.num_ctx}, {@code think} — and to exercise the tolerant SSE
 * parser against both {@code data:} framing and bare newline-delimited JSON.
 *
 * <p>Streaming responses are sent with chunked transfer encoding and flushed per
 * line, so the client genuinely observes incremental delivery.
 */
public final class StubLlmServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile Reply chat = Reply.sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}", "[DONE]");
    private volatile Reply chatStream = null;
    private volatile Reply models = Reply.json("{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}");
    private volatile long firstFrameDelayMs = 0;

    public StubLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Streams {@code data:} frames, as a standards-compliant server would. */
    public void chatSse(String... frames) {
        chat = Reply.sse(frames);
    }

    /**
     * Replies with these frames only to requests whose body carries {@code "stream":true}.
     * Lets a test answer the non-streaming tool-planning request with a tool call and the
     * streaming follow-up with deltas, which a single shared reply cannot express.
     */
    public void chatStreamSse(String... frames) {
        chatStream = Reply.sse(frames);
    }

    /** Streams these frames, but waits {@code delayMillis} before the first one. */
    public void chatSseDelayed(long delayMillis, String... frames) {
        firstFrameDelayMs = delayMillis;
        chat = Reply.sse(frames);
    }

    /** Streams bare newline-delimited JSON, with no SSE framing at all. */
    public void chatLines(String... frames) {
        chat = Reply.lines(frames);
    }

    /** Returns a single non-streaming JSON body. */
    public void chatJson(String body) {
        chat = Reply.json(body);
    }

    /** Returns {@code status} with {@code body}, for error-path tests. */
    public void chatFailure(int status, String body) {
        chat = Reply.failure(status, body);
    }

    public void modelsJson(String body) {
        models = Reply.json(body);
    }

    public JsonNode lastRequest() {
        try {
            String body = lastBody.get();
            return body == null || body.isBlank() ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Captured request body was not JSON: " + lastBody.get(), e);
        }
    }

    public String lastRequestBody() {
        return lastBody.get();
    }

    public String lastPath() {
        return lastPath.get();
    }

    public int requests() {
        return requestCount.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        String path = exchange.getRequestURI().getPath();
        lastPath.set(path);
        Reply reply = path.endsWith("/models") ? models
                : chatStream != null && lastBody.get().contains("\"stream\":true") ? chatStream
                : chat;

        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        if (reply.chunked()) {
            exchange.sendResponseHeaders(reply.status(), 0);
            try (OutputStream out = exchange.getResponseBody()) {
                boolean first = true;
                for (String line : reply.body().split("\n", -1)) {
                    if (first && firstFrameDelayMs > 0) {
                        try {
                            Thread.sleep(firstFrameDelayMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    first = false;
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                }
            }
        } else {
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Reply(int status, String contentType, String body, boolean chunked) {

        static Reply sse(String... frames) {
            StringBuilder sb = new StringBuilder();
            for (String frame : frames) {
                sb.append("data: ").append(frame).append("\n\n");
            }
            return new Reply(200, "text/event-stream", sb.toString(), true);
        }

        static Reply lines(String... frames) {
            return new Reply(200, "application/x-ndjson", String.join("\n", frames) + "\n", true);
        }

        static Reply json(String body) {
            return new Reply(200, "application/json", body, false);
        }

        static Reply failure(int status, String body) {
            return new Reply(status, "application/json", body, false);
        }
    }
}