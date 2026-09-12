package davejones74.campanionai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal client for the Ollama chat API. Sends a non-streaming chat request
 * and returns the model's reply text.
 */
public class LlmClient {
    private final String model;
    private final String baseUrl;
    private final double temperature;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(String model, String baseUrl, double temperature) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
    }

    private String chatRequest(List<ChatMessage> messages, boolean stream) throws LlmException {
        try {
            String body = mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .put("model", model)
                            .put("stream", stream)
                            .<com.fasterxml.jackson.databind.node.ObjectNode>set("messages", messagesJson(messages))
                            .set("options", mapper.createObjectNode().put("temperature", temperature)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(stream ? 300 : 120))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to reach Ollama at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Sends the conversation to Ollama and returns the assistant's reply.
     */
    public String chat(List<ChatMessage> messages) throws LlmException {
        try {
            JsonNode root = mapper.readTree(chatRequest(messages, false));
            JsonNode error = root.get("error");
            if (error != null) {
                throw new LlmException("Ollama error: " + error.asText());
            }
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmException("Ollama response had no message content.");
            }
            return content.asText().trim();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to reach Ollama at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Streams a chat completion, feeding each delta to {@code handler}.
     */
    public void chatStream(List<ChatMessage> messages, ChunkHandler handler) throws LlmException {
        try {
            String body = mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .put("model", model)
                            .put("stream", true)
                            .<com.fasterxml.jackson.databind.node.ObjectNode>set("messages", messagesJson(messages))
                            .set("options", mapper.createObjectNode().put("temperature", temperature)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String err = response.body().limit(20).reduce((a, b) -> a + "\n" + b).orElse("");
                throw new LlmException("Ollama returned HTTP " + response.statusCode() + ": " + err);
            }

            try (Stream<String> lines = response.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next().trim();
                    if (line.isEmpty()) continue;
                    JsonNode root;
                    try {
                        root = mapper.readTree(line);
                    } catch (Exception ignored) {
                        continue;
                    }
                    JsonNode error = root.get("error");
                    if (error != null) {
                        throw new LlmException("Ollama error: " + error.asText());
                    }
                    if (root.path("done").asBoolean(false)) continue;
                    String delta = root.path("message").path("content").asText("");
                    if (!delta.isEmpty()) handler.onDelta(delta);
                }
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to reach Ollama at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private JsonNode messagesJson(List<ChatMessage> messages) {
        var array = mapper.createArrayNode();
        for (ChatMessage msg : messages) {
            array.add(mapper.createObjectNode()
                    .put("role", msg.role())
                    .put("content", msg.content()));
        }
        return array;
    }

    public record ChatMessage(String role, String content) {
    }

    /**
     * Callback receiving streaming content deltas as they arrive.
     */
    @FunctionalInterface
    public interface ChunkHandler {
        void onDelta(String delta);
    }

    public static class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}