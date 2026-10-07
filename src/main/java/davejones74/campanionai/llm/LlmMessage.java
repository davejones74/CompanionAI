package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One message in a chat conversation.
 *
 * <p>Replaces {@code LlmClient.ChatMessage}. Declared in its own top-level type
 * rather than nested inside the transport so that application and retrieval
 * code can depend on the message shape without depending on any provider.
 *
 * <p>{@code toolCalls} is set on assistant messages that requested tools and
 * {@code toolCallId} is set on role {@code tool} result messages; both are
 * almost always null. {@code images} carries any image parts attached to the
 * message; {@code List.of()} for text-only messages.
 */
public record LlmMessage(String role, String content, JsonNode toolCalls, String toolCallId,
                         List<ImagePart> images) {

    public LlmMessage {
        role = role == null ? "user" : role;
        content = content == null ? "" : content;
        images = images == null ? List.of() : List.copyOf(images);
    }

    public LlmMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    public LlmMessage(String role, String content, JsonNode toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }

    public static LlmMessage toolCall(JsonNode toolCalls) {
        return new LlmMessage("assistant", "", toolCalls, null, List.of());
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId, List.of());
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null, List.of());
    }

    public static LlmMessage withImage(String role, String content, ImagePart image) {
        return new LlmMessage(role, content, null, null, new ArrayList<>(List.of(image)));
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
