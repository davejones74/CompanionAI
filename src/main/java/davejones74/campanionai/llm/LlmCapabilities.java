package davejones74.campanionai.llm;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable set of {@link LlmCapability} values for one provider.
 *
 * <p>Application code queries capabilities instead of testing for a runtime by
 * name, so that adding a provider never requires touching consumer logic.
 */
public record LlmCapabilities(Set<LlmCapability> values) {

    public LlmCapabilities {
        values = values == null || values.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    public static LlmCapabilities of(LlmCapability... capabilities) {
        EnumSet<LlmCapability> set = EnumSet.noneOf(LlmCapability.class);
        Collections.addAll(set, capabilities);
        return new LlmCapabilities(set);
    }

    public static LlmCapabilities none() {
        return new LlmCapabilities(Set.of());
    }

    public boolean has(LlmCapability capability) {
        return values.contains(capability);
    }

    public boolean hasAll(LlmCapability... capabilities) {
        return values.containsAll(Set.of(capabilities));
    }

    public String describe() {
        if (values.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (LlmCapability capability : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(capability.name());
        }
        return sb.toString();
    }
}