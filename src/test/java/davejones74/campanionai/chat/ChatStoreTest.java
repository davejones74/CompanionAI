package davejones74.campanionai.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void createListGet() throws Exception {
        ChatStore store = new ChatStore(dataDir);
        Chat chat = store.create("Where should I run in Kyoto?");
        assertNotNull(chat.id());
        assertEquals("Where should I run in Kyoto?", chat.title());
        List<Chat> chats = store.list();
        assertEquals(1, chats.size());
        assertEquals(chat.id(), store.get(chat.id()).id());
        assertNull(store.get("missing"));
    }

    @Test
    void appendRenameDelete() throws Exception {
        ChatStore store = new ChatStore(dataDir);
        Chat chat = store.create("New chat");
        store.append(chat.id(), new ChatMessage("user", "hello"));
        store.append(chat.id(), new ChatMessage("assistant", "hi there"));
        List<ChatMessage> msgs = store.loadMessages(chat.id());
        assertEquals(2, msgs.size());
        assertEquals("user", msgs.get(0).role());
        assertEquals("assistant", msgs.get(1).role());
        store.rename(chat.id(), "Greetings");
        assertEquals("Greetings", store.get(chat.id()).title());
        store.delete(chat.id());
        assertNull(store.get(chat.id()));
        assertTrue(store.loadMessages(chat.id()).isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void persistsAcrossInstances() throws Exception {
        ChatStore first = new ChatStore(dataDir);
        Chat chat = first.create("Persist me");
        first.append(chat.id(), new ChatMessage("user", "saved?"));
        ChatStore second = new ChatStore(dataDir);
        assertEquals("Persist me", second.get(chat.id()).title());
        assertEquals(1, second.loadMessages(chat.id()).size());
    }

    @Test
    void replaceMessages() throws Exception {
        ChatStore store = new ChatStore(dataDir);
        Chat chat = store.create("x");
        store.append(chat.id(), new ChatMessage("user", "a"));
        store.replaceMessages(chat.id(), List.of(new ChatMessage("user", "b")));
        List<ChatMessage> msgs = store.loadMessages(chat.id());
        assertEquals(1, msgs.size());
        assertEquals("b", msgs.get(0).content());
    }
}
