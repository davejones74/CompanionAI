package davejones74.campanionai.retrieval;

public record LiveContext(String promptBlock, boolean attempted, boolean failed) {

    public LiveContext {
        promptBlock = promptBlock == null ? "" : promptBlock;
    }

    public boolean isBlank() {
        return promptBlock.isBlank();
    }

    public static LiveContext empty() {
        return new LiveContext("", false, false);
    }
}