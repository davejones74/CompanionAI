package davejones74.campanionai.chat;

import java.time.Instant;

public record Chat(String id, String title, Instant createdAt, Instant updatedAt) {

    public Chat {
        id = id == null ? "" : id;
        title = title == null ? "New chat" : title;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
