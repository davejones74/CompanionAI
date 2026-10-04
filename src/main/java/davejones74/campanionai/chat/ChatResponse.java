package davejones74.campanionai.chat;

import davejones74.campanionai.FileRef;
import davejones74.campanionai.Source;

import java.util.List;

public record ChatResponse(String reply, boolean offline, List<Source> sources, List<FileRef> files,
                          ContextUsage contextUsage, String chatId) {

    public ChatResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        files = files == null ? List.of() : List.copyOf(files);
        contextUsage = contextUsage == null ? ContextUsage.of(0, 1) : contextUsage;
        chatId = chatId == null ? "" : chatId;
        reply = reply == null ? "" : reply;
    }
}
