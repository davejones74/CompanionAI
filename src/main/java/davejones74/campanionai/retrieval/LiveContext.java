package davejones74.campanionai.retrieval;

import davejones74.campanionai.Source;

import java.util.ArrayList;
import java.util.List;

public record LiveContext(String promptBlock, boolean attempted, boolean failed, List<Source> sources) {

    public LiveContext {
        promptBlock = promptBlock == null ? "" : promptBlock;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public LiveContext(String promptBlock, boolean attempted, boolean failed) {
        this(promptBlock, attempted, failed, List.of());
    }

    public boolean isBlank() {
        return promptBlock.isBlank();
    }

    public static LiveContext empty() {
        return new LiveContext("", false, false, List.of());
    }
}
