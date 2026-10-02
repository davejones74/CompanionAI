package davejones74.campanionai.llm;

/**
 * One message in a chat conversation.
 *
 * <p>Replaces {@code LlmClient.ChatMessage}. Declared in its own top-level type
 * rather than nested inside the transport so that application and retrieval
 * code can depend on the message shape without depending on any provider.
 */
public record LlmMessage(String role, String content) {
}