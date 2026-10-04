package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One message in a chat conversation.
 *
 * <p>Replaces {@code LlmClient.ChatMessage}. Declared in its own top-level type
 * rather than nested inside the transport so that application and retrieval
 * code can depend on the message shape without depending on any provider.
 *
 * <p>{@code toolCalls} is set on assistant messages that requested tools and
 * {@code toolCallId} is set on role {@code tool} result messages; both are
 * almost always null.
 */
public record LlmMessage(String role, String content, JsonNode toolCalls, String toolCallId) {

    public LlmMessage {
        role = role == null ? "user" : role;
        content = content == null ? "" : content;
    }

    public LlmMessage(String role, String content) {
        this(role, content, null, null);
    }

    public static LlmMessage toolCall(JsonNode toolCalls) {
        return new LlmMessage("assistant", "", toolCalls, null);
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
