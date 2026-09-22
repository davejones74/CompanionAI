package davejones74.campanionai.retrieval;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class StubServer implements AutoCloseable {

    final int port;
    private final HttpServer server;
    private final ConcurrentHashMap<String, String> responses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastQuery = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requestCount.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            lastQuery.put(path, ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
            String body = responses.getOrDefault(path, "{}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    void on(String path, String body) {
        responses.put(path, body);
    }

    int requests() {
        return requestCount.get();
    }

    String query(String path) {
        return lastQuery.getOrDefault(path, "");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}