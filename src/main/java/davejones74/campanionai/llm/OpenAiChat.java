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
        ArrayNode array = mapper.createArrayNode();
        for (LlmMessage message : messages) {
            array.add(mapper.createObjectNode()
                    .put("role", message.role())
                    .put("content", message.content()));
        }
        ObjectNode node = mapper.createObjectNode()
                .put("model", model)
                .put("stream", stream)
                .put("temperature", temperature);
        node.set("messages", array);
        return node;
    }

    static String chatCompletionsPath() {
        return "/v1/chat/completions";
    }

    static String modelsPath() {
        return "/v1/models";
    }
}