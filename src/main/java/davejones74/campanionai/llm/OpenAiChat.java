package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Builds the OpenAI-standard chat completion envelope shared by every
 * OpenAI-compatible runtime.
 *
 * <p>This exists so that {@link OpenAiCompatTransport} stays a pure transport and
 * so that the two providers do not duplicate the same eight fields. It contains
 * nothing runtime-specific: everything beyond the standard envelope is added by
 * the calling provider.
 */
final class OpenAiChat {

    private OpenAiChat() {
    }

    static ObjectNode envelope(ObjectMapper mapper,
                               String model,
                               List<LlmMessage> messages,
                               double temperature,
                               boolean stream) {
        return envelope(mapper, model, messages, temperature, stream, null);
    }

    static ObjectNode envelope(ObjectMapper mapper,
                               String model,
                               List<LlmMessage> messages,
                               double temperature,
                               boolean stream,
                               List<com.fasterxml.jackson.databind.JsonNode> tools) {
        ArrayNode array = mapper.createArrayNode();
        for (LlmMessage message : messages) {
            ObjectNode m = mapper.createObjectNode()
                    .put("role", message.role())
                    .put("content", message.content());
            if (message.toolCalls() != null) {
                m.set("tool_calls", message.toolCalls());
            }
            if (message.toolCallId() != null) {
                m.put("tool_call_id", message.toolCallId());
            }
            array.add(m);
        }
        ObjectNode node = mapper.createObjectNode()
                .put("model", model)
                .put("stream", stream)
                .put("temperature", temperature);
        node.set("messages", array);
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolNodes = mapper.createArrayNode();
            for (com.fasterxml.jackson.databind.JsonNode t : tools) {
                toolNodes.add(t);
            }
            node.set("tools", toolNodes);
        }
        return node;
    }

    static String chatCompletionsPath() {
        return "/v1/chat/completions";
    }

    static String modelsPath() {
        return "/v1/models";
    }
}