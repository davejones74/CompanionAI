package davejones74.campanionai.chat;

import davejones74.campanionai.FileRef;
import davejones74.campanionai.Source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record ChatMessage(String role, String content, Instant createdAt,
                          List<Source> sources, List<FileRef> files) {

    public ChatMessage {
        role = role == null ? "user" : role;
        content = content == null ? "" : content;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        sources = sources == null ? List.of() : List.copyOf(sources);
        files = files == null ? List.of() : List.copyOf(files);
    }

    public ChatMessage(String role, String content) {
        this(role, content, Instant.now(), List.of(), List.of());
    }

    public ChatMessage withSources(List<Source> newSources) {
        List<Source> list = newSources == null ? new ArrayList<>() : new ArrayList<>(newSources);
        return new ChatMessage(role, content, createdAt, List.copyOf(list), files);
    }

    public ChatMessage withFiles(List<FileRef> newFiles) {
        List<FileRef> list = newFiles == null ? new ArrayList<>() : new ArrayList<>(newFiles);
        return new ChatMessage(role, content, createdAt, sources, List.copyOf(list));
    }
}
