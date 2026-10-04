package davejones74.campanionai.retrieval;

import davejones74.campanionai.Source;

import java.util.List;

/**
 * What a request's live lookup produced, and how it was gathered.
 *
 * <p>{@code profile} is carried rather than recomputed by the caller, because two components now
 * need to agree on it: the prompt builder, which reports the scope and counts, and the cloud
 * fallback policy, which refuses to send research requests anywhere. Recomputing the profile at
 * the second site would let the two disagree, and the disagreement would show up as a research
 * request leaving the machine.
 */
public record LiveContext(String promptBlock,
                          boolean attempted,
                          boolean failed,
                          List<Source> sources,
                          WebSearchProfile profile) {

    public LiveContext {
        promptBlock = promptBlock == null ? "" : promptBlock;
        sources = sources == null ? List.of() : List.copyOf(sources);
        profile = profile == null ? WebSearchProfile.LOOKUP : profile;
    }

    public LiveContext(String promptBlock, boolean attempted, boolean failed) {
        this(promptBlock, attempted, failed, List.of(), WebSearchProfile.LOOKUP);
    }

    public LiveContext(String promptBlock, boolean attempted, boolean failed, List<Source> sources) {
        this(promptBlock, attempted, failed, sources, WebSearchProfile.LOOKUP);
    }

    public boolean isBlank() {
        return promptBlock.isBlank();
    }

    public static LiveContext empty() {
        return new LiveContext("", false, false, List.of(), WebSearchProfile.LOOKUP);
    }
}