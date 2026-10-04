package davejones74.campanionai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<LlmMessage> MESSAGES = List.of(
            new LlmMessage("system", "be terse"),
            new LlmMessage("user", "make me a file"));

    private static JsonNode createFileTool() {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = MAPPER.createObjectNode();
        fn.put("name", "create_file");
        fn.set("parameters", MAPPER.createObjectNode().put("type", "object"));
        tool.set("function", fn);
        return tool;
    }

    private static String toolCallReply() {
        ObjectNode fn = MAPPER.createObjectNode();
        fn.put("name", "create_file");
        fn.put("arguments", "{\"filename\":\"a.md\",\"content\":\"# A\"}");
        ObjectNode call = MAPPER.createObjectNode();
        call.put("id", "call_1");
        call.put("type", "function");
        call.set("function", fn);
        ObjectNode message = MAPPER.createObjectNode();
        message.set("tool_calls", MAPPER.createArrayNode().add(call));
        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("message", message);
        return MAPPER.createObjectNode()
                .set("choices", MAPPER.createArrayNode().add(choice))
                .toString();
    }

    @Test
    void ollamaChatMessageSendsToolsAndParsesToolCalls() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(toolCallReply());
            OllamaProvider p = new OllamaProvider("m", s.url(), 0.7, null);
            JsonNode message = p.chatMessage(MESSAGES, List.of(createFileTool()));
            assertTrue(message.path("tool_calls").isArray());
            assertEquals("create_file", message.path("tool_calls").get(0).path("function").path("name").asText());
            JsonNode sent = s.lastRequest();
            assertTrue(sent.path("tools").isArray());
            assertEquals("create_file", sent.path("tools").get(0).path("function").path("name").asText());
        }
    }

    @Test
    void fastFlowLmChatMessageSendsToolsAndParsesToolCalls() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson(toolCallReply());
            FastFlowLmProvider p = new FastFlowLmProvider("m", s.url(), 0.7, false);
            JsonNode message = p.chatMessage(MESSAGES, List.of(createFileTool()));
            assertTrue(message.path("tool_calls").isArray());
            JsonNode sent = s.lastRequest();
            assertTrue(sent.path("tools").isArray());
            assertTrue(sent.has("think"));
        }
    }

    @Test
    void toolResultsAreSerialised() throws Exception {
        try (StubLlmServer s = new StubLlmServer()) {
            s.chatJson("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
            OllamaProvider p = new OllamaProvider("m", s.url(), 0.7, null);
            JsonNode toolCalls = MAPPER.readTree("[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"create_file\",\"arguments\":\"{}\"}}]");
            p.chatMessage(List.of(
                    new LlmMessage("user", "hi"),
                    LlmMessage.toolCall(toolCalls),
                    LlmMessage.toolResult("c1", "{\"status\":\"created\"}")), List.of());
            JsonNode sent = s.lastRequest();
            assertEquals("tool", sent.path("messages").get(2).path("role").asText());
            assertEquals("c1", sent.path("messages").get(2).path("tool_call_id").asText());
            assertTrue(sent.path("messages").get(1).path("tool_calls").isArray());
        }
    }
}
