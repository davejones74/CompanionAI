package davejones74.campanionai.chat;

import davejones74.campanionai.FileRef;

import java.util.List;

public record ToolResult(List<FileRef> files) {

    public ToolResult {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
