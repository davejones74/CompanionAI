package davejones74.campanionai.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public final class ChatStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path dir;

    public ChatStore(Path dataDir) throws IOException {
        this.dir = dataDir.resolve("chats");
        Files.createDirectories(dir);
    }

    public List<Chat> list() throws IOException {
        List<Chat> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            Chat chat = readChat(p);
                            if (chat != null) {
                                result.add(chat);
                            }
                        } catch (IOException e) {
                            // skip unreadable chat
                        }
                    });
        }
        result.sort(Comparator.comparing(Chat::updatedAt).reversed());
        return result;
    }

    public Chat create(String title) throws IOException {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String safeTitle = title == null || title.isBlank() ? "New chat" : title.trim();
        if (safeTitle.length() > 255) {
            safeTitle = safeTitle.substring(0, 255);
        }
        Chat chat = new Chat(id, safeTitle, now, now);
        writeChat(chat);
        writeMessages(id, List.of());
        return chat;
    }

    public Chat get(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return null;
        }
        Path p = dir.resolve(id + ".json");
        if (!Files.exists(p)) {
            return null;
        }
        return readChat(p);
    }

    public void delete(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return;
        }
        Path chatFile = dir.resolve(id + ".json");
        Path msgFile = dir.resolve(id + "_messages.json");
        Files.deleteIfExists(chatFile);
        Files.deleteIfExists(msgFile);
    }

    public void rename(String id, String title) throws IOException {
        if (id == null || id.isBlank()) {
            return;
        }
        Path p = dir.resolve(id + ".json");
        if (!Files.exists(p)) {
            return;
        }
        Chat chat = readChat(p);
        if (chat == null) {
            return;
        }
        String safeTitle = title == null || title.isBlank() ? chat.title() : title.trim();
        if (safeTitle.length() > 255) {
            safeTitle = safeTitle.substring(0, 255);
        }
        Chat updated = new Chat(chat.id(), safeTitle, chat.createdAt(), Instant.now());
        writeChat(updated);
    }

    public List<ChatMessage> loadMessages(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return List.of();
        }
        Path p = dir.resolve(id + "_messages.json");
        if (!Files.exists(p)) {
            return List.of();
        }
        String json = Files.readString(p, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return List.of();
        }
        List<ChatMessage> messages = MAPPER.readValue(json, new TypeReference<>() {
        });
        return messages == null ? List.of() : messages;
    }

    public void append(String chatId, ChatMessage message) throws IOException {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>(loadMessages(chatId));
        messages.add(message);
        writeMessages(chatId, messages);
        touch(chatId);
    }

    public void replaceMessages(String chatId, List<ChatMessage> messages) throws IOException {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        writeMessages(chatId, messages == null ? List.of() : messages);
        touch(chatId);
    }

    private void touch(String chatId) throws IOException {
        Chat chat = get(chatId);
        if (chat == null) {
            return;
        }
        Chat updated = new Chat(chat.id(), chat.title(), chat.createdAt(), Instant.now());
        writeChat(updated);
    }

    private void writeChat(Chat chat) throws IOException {
        Path p = dir.resolve(chat.id() + ".json");
        String json = MAPPER.writeValueAsString(chat);
        Files.writeString(p, json, StandardCharsets.UTF_8);
    }

    private void writeMessages(String chatId, List<ChatMessage> messages) throws IOException {
        Path p = dir.resolve(chatId + "_messages.json");
        String json = MAPPER.writeValueAsString(messages);
        Files.writeString(p, json, StandardCharsets.UTF_8);
    }

    private Chat readChat(Path p) throws IOException {
        if (!Files.exists(p)) {
            return null;
        }
        String json = Files.readString(p, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return null;
        }
        return MAPPER.readValue(json, Chat.class);
    }
}
