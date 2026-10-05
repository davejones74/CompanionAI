package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * HTTP, framing and error handling for OpenAI-compatible runtimes.
 *
 * <p>Strict about what it sends and tolerant about what it accepts:
 *
 * <ul>
 *   <li>Request bodies are always well-formed OpenAI chat completions, because
 *       the providers build them from {@link OpenAiChat}.</li>
 *   <li>Streaming responses are accepted both as standards-compliant SSE
 *       ({@code data:} frames terminated by {@code data: [DONE]}) and as bare
 *       newline-delimited JSON, because the installed runtime behaviour has not
 *       been verified on every target host yet.</li>
 * </ul>
 *
 * <p>This class deliberately holds no capability or runtime knowledge. Whether a
 * request carries {@code options.num_ctx} or {@code think} is decided by the
 * provider, not here. Tighten the streaming parser once the framing of each
 * deployed runtime has been confirmed.
 */
public final class OpenAiCompatTransport {

    static final int CONNECT_TIMEOUT_SECONDS = 10;
    static final int COMPLETE_TIMEOUT_SECONDS = 120;
    static final int STREAM_TIMEOUT_SECONDS = 300;

    private static final String DONE = "[DONE]";

    private final String baseUrl;
    private final String providerLabel;
    /**
     * Bearer credential sent on every request, or {@code null} for a runtime that needs none.
     *
     * <p>Local runtimes are unauthenticated. A hosted endpoint is not, and a missing
     * {@code Authorization} header is the difference between a 401 and a working fallback.
     */
    private final String bearerToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    OpenAiCompatTransport(String baseUrl, String providerLabel) {
        this(baseUrl, providerLabel, null);
    }

    OpenAiCompatTransport(String baseUrl, String providerLabel, String bearerToken) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.providerLabel = providerLabel;
        this.bearerToken = bearerToken == null || bearerToken.isBlank() ? null : bearerToken.trim();
    }

    String baseUrl() {
        return baseUrl;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    /** POSTs a chat completion body and returns the assistant reply, trimmed. */
    String complete(String path, String body) throws LlmException {
        JsonNode message = completeMessage(path, body);
        JsonNode content = message.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            return content.asText().trim();
        }
        throw new LlmException(providerLabel + " response had no message content.");
    }

    /** POSTs a chat completion body and returns the raw assistant message node. */
    JsonNode completeMessage(String path, String body) throws LlmException {
        try {
            HttpRequest request = post(path, body, COMPLETE_TIMEOUT_SECONDS);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException(providerLabel + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode root = parse(response.body());
            throwIfError(root);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (!message.isMissingNode() && !message.isNull()) {
                    return message;
                }
            }
            throw new LlmException(providerLabel + " response had no message content.");
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable(e);
        }
    }

    /** POSTs a streaming chat completion body, feeding deltas to {@code handler}. */
    void stream(String path, String body, LlmProvider.ChunkHandler handler) throws LlmException {
        try {
            HttpRequest request = post(path, body, STREAM_TIMEOUT_SECONDS);
            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String err = response.body().limit(20).reduce((a, b) -> a + "\n" + b).orElse("");
                throw new LlmException(providerLabel + " returned HTTP "
                        + response.statusCode() + ": " + err);
            }
            readStream(response.body(), handler);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // Thrown by the ChunkHandler itself (for example when the downstream
            // client disconnected); it must not be relabelled as an upstream failure.
            throw e;
        } catch (Exception e) {
            throw unreachable(e);
        }
    }

    /** GETs a path and returns the parsed JSON body. */
    JsonNode getJson(String path) throws LlmException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri(path))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(COMPLETE_TIMEOUT_SECONDS))
                    .GET();
            authorize(builder);
            HttpRequest request = builder.build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException(providerLabel + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode root = parse(response.body());
            throwIfError(root);
            return root;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable(e);
        }
    }

    private HttpRequest post(String path, String body, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri(path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        authorize(builder);
        return builder.build();
    }

    private void authorize(HttpRequest.Builder builder) {
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private void readStream(Stream<String> lines, LlmProvider.ChunkHandler handler) throws LlmException {
        String finishReason = null;
        Integer completionTokens = null;
        try (Stream<String> body = lines) {
            Iterator<String> it = body.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null) {
                    continue;
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (line.regionMatches(true, 0, "data:", 0, 5)) {
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if (DONE.equals(data)) {
                        handler.onComplete(new StreamCompletion(finishReason, completionTokens));
                        return;
                    }
                    line = data;
                } else if (startsWithField(line, "event:", "id:", "retry:")) {
                    continue;
                }
                JsonNode root = parseQuietly(line);
                if (root == null) {
                    continue;
                }
                throwIfError(root);
                String reason = finishReason(root);
                if (reason != null) {
                    finishReason = reason;
                }
                Integer tokens = completionTokens(root);
                if (tokens != null) {
                    completionTokens = tokens;
                }
                if (root.path("done").asBoolean(false)) {
                    continue;
                }
                deltaThinking(root, handler);
                String delta = deltaContent(root);
                if (!delta.isEmpty()) {
                    handler.onDelta(delta);
                }
            }
        }
        handler.onComplete(new StreamCompletion(finishReason, completionTokens));
    }

    private static String finishReason(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode reason = choices.get(0).path("finish_reason");
        if (reason.isTextual() && !reason.asText().isBlank()) {
            return reason.asText();
        }
        return null;
    }

    private static Integer completionTokens(JsonNode root) {
        JsonNode tokens = root.path("usage").path("completion_tokens");
        return tokens.isInt() || tokens.isLong() ? tokens.asInt() : null;
    }

    private static boolean startsWithField(String line, String... prefixes) {
        for (String prefix : prefixes) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static String deltaContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode first = choices.get(0);
        JsonNode delta = first.path("delta").path("content");
        if (!delta.isMissingNode() && !delta.isNull()) {
            return delta.asText("");
        }
        JsonNode message = first.path("message").path("content");
        if (!message.isMissingNode() && !message.isNull()) {
            return message.asText("");
        }
        return "";
    }

    private static void deltaThinking(JsonNode root, LlmProvider.ChunkHandler handler) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode first = choices.get(0);
        JsonNode delta = first.path("delta");
        if (delta.isMissingNode() || delta.isNull()) {
            return;
        }
        String[] keys = { "reasoning_content", "reasoning", "thinking", "thought" };
        for (String k : keys) {
            if (delta.has(k) && !delta.get(k).isNull()) {
                String t = delta.get(k).asText("");
                if (!t.isEmpty()) {
                    handler.onThinking(t);
                }
            }
        }
    }

    private void throwIfError(JsonNode root) throws LlmException {
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new LlmException(providerLabel + " error: " + errorText(error));
        }
    }

    private static String errorText(JsonNode error) {
        if (error.isTextual()) {
            return error.asText();
        }
        JsonNode message = error.path("message");
        if (!message.isMissingNode() && !message.isNull()) {
            return message.asText();
        }
        return error.toString();
    }

    private JsonNode parse(String body) throws LlmException {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new LlmException(providerLabel + " returned a response that could not be parsed: "
                    + e.getMessage(), e);
        }
    }

    private JsonNode parseQuietly(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception ignored) {
            return null;
        }
    }

    private LlmException unreachable(Exception e) {
        return new LlmException("Failed to reach " + providerLabel + " at " + baseUrl
                + ": " + e.getMessage(), e);
    }

    private static String stripTrailingSlash(String url) {
        String value = url;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static List<String> readModelIds(JsonNode root) {
        List<String> ids = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode entry : data) {
                String id = entry.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}