package davejones74.campanionai.chat;

import davejones74.campanionai.ModelServlet;
import davejones74.campanionai.llm.LlmMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRestorationTest {

    @TempDir
    Path dataDir;

    @Test
    void activeChatHistoryIsRestoredPerChat() throws Exception {
        ChatStore store = new ChatStore(dataDir);
        Chat a = store.create("Japan");
        store.append(a.id(), new ChatMessage("user", "What do you know about Dave's trip to Japan?"));
        store.append(a.id(), new ChatMessage("assistant", "He visited Kyoto."));
        store.append(a.id(), new ChatMessage("user", "What about Kyoto?"));
        store.append(a.id(), new ChatMessage("assistant", "Kyoto has temples."));

        Chat b = store.create("Mercedes");
        store.append(b.id(), new ChatMessage("user", "What car does Dave drive?"));
        store.append(b.id(), new ChatMessage("assistant", "A Mercedes CLE."));

        List<LlmMessage> forA = ModelServlet.buildChatMessages(store, new java.util.ArrayList<>(), "sys", "What about running there?", a.id(), 8000, 40);
        List<LlmMessage> forB = ModelServlet.buildChatMessages(store, new java.util.ArrayList<>(), "sys", "What about its engine?", b.id(), 8000, 40);

        // Chat A history must contain Japan/Kyoto, not Mercedes
        String joinedA = forA.stream().map(LlmMessage::content).reduce("", (x, y) -> x + " | " + y);
        assertTrue(joinedA.contains("Japan"), joinedA);
        assertTrue(joinedA.contains("Kyoto"), joinedA);
        assertTrue(!joinedA.contains("CLE"), joinedA);
        assertEquals("What about running there?", forA.get(forA.size() - 1).content());

        // Chat B history must contain Mercedes, not Japan
        String joinedB = forB.stream().map(LlmMessage::content).reduce("", (x, y) -> x + " | " + y);
        assertTrue(joinedB.contains("Mercedes"), joinedB);
        assertTrue(!joinedB.contains("Kyoto"), joinedB);
        assertEquals("What about its engine?", forB.get(forB.size() - 1).content());
    }

    @Test
    void messagesSurviveRestartAndStayIndependent() throws Exception {
        ChatStore first = new ChatStore(dataDir);
        Chat a = first.create("Japan");
        first.append(a.id(), new ChatMessage("user", "hi"));
        Chat b = first.create("Mercedes");
        first.append(b.id(), new ChatMessage("user", "hello"));

        ChatStore restarted = new ChatStore(dataDir);
        assertEquals(1, restarted.loadMessages(a.id()).size());
        assertEquals(1, restarted.loadMessages(b.id()).size());
        assertEquals("hi", restarted.loadMessages(a.id()).get(0).content());

        restarted.delete(b.id());
        assertEquals(1, restarted.loadMessages(a.id()).size());
        restarted.rename(a.id(), "Japan Trip");
        ChatStore again = new ChatStore(dataDir);
        assertEquals("Japan Trip", again.get(a.id()).title());
        assertEquals(1, again.list().size());
    }
}
