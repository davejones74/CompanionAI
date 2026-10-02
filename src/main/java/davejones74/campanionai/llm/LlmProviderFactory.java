package davejones74.campanionai.llm;

import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Turns configuration into an {@link LlmProvider}.
 *
 * <p>This is the only place permitted to name a runtime. An unrecognised provider
 * fails startup rather than silently defaulting, because silently falling back
 * would deploy the X1 Pro against the wrong runtime.
 *
 * <p>Lookup order is system property, then environment variable, then default.
 *
 * <p>The existing configuration namespace is misspelled {@code campanionai.*},
 * while the provider-aware keys were requested under the correct spelling
 * {@code companionai.llm.*}. Both are accepted, correctly spelled first. The
 * pre-existing {@code campanionai.model}, {@code campanionai.temperature},
 * {@code campanionai.numCtx} and {@code campanionai.ollamaUrl} keys keep working
 * unchanged.
 */
public final class LlmProviderFactory {

    public static final String DEFAULT_PROVIDER = "ollama";
    public static final String DEFAULT_MODEL = "qwen3.6:27b";
    public static final double DEFAULT_TEMPERATURE = 0.7;

    private static final Logger LOG = LogManager.getLogger(LlmProviderFactory.class);

    private static final String[] PROVIDER_KEYS = {
            "companionai.llm.provider", "campanionai.llm.provider"};
    private static final String[] BASE_URL_KEYS = {
            "companionai.llm.baseUrl", "campanionai.llm.baseUrl"};
    private static final String[] MODEL_KEYS = {
            "companionai.llm.model", "campanionai.model"};
    private static final String[] TEMPERATURE_KEYS = {
            "companionai.llm.temperature", "campanionai.temperature"};
    private static final String[] NUM_CTX_KEYS = {
            "companionai.llm.numCtx", "campanionai.numCtx"};
    private static final String[] THINK_KEYS = {
            "companionai.llm.think", "campanionai.llm.think"};

    private static final String LEGACY_OLLAMA_URL_KEY = "campanionai.ollamaUrl";

    private LlmProviderFactory() {
    }

    public static LlmProvider fromSystemProperties() {
        String providerName = orDefault(lookup(PROVIDER_KEYS, "LLM_PROVIDER"), DEFAULT_PROVIDER);
        String model = orDefault(lookup(MODEL_KEYS, "LLM_MODEL"), DEFAULT_MODEL);
        double temperature = decimal(lookup(TEMPERATURE_KEYS, "LLM_TEMPERATURE"), DEFAULT_TEMPERATURE);
        Integer numCtx = integer(lookup(NUM_CTX_KEYS, "LLM_NUM_CTX"));
        boolean think = bool(lookup(THINK_KEYS, "LLM_THINK"), false);

        String baseUrl = resolveBaseUrl(providerName, lookup(BASE_URL_KEYS, "LLM_BASE_URL"));
        LlmProvider provider = create(providerName, baseUrl, model, temperature, numCtx, think);

        if (numCtx != null && !provider.capabilities().has(LlmCapability.PER_REQUEST_CONTEXT_LENGTH)) {
            LOG.warn("{} does not support a per-request context length; ignoring {}.",
                    provider.providerName(), NUM_CTX_KEYS[1]);
        }
        return provider;
    }

    public static LlmProvider create(String providerName,
                                     String baseUrl,
                                     String model,
                                     double temperature,
                                     Integer numCtx,
                                     boolean think) {
        String name = normalize(providerName);
        if (name == null) {
            name = DEFAULT_PROVIDER;
        }
        String resolvedBaseUrl = isBlank(baseUrl) ? requireDefaultBaseUrl(name) : baseUrl.trim();
        String resolvedModel = isBlank(model) ? DEFAULT_MODEL : model.trim();

        return switch (name) {
            case "ollama" -> new OllamaProvider(resolvedModel, resolvedBaseUrl, temperature, numCtx);
            case "fastflowlm" -> new FastFlowLmProvider(
                    resolvedModel, resolvedBaseUrl, temperature, think);
            default -> throw unknownProvider(providerName);
        };
    }

    private static String resolveBaseUrl(String providerName, String configured) {
        if (!isBlank(configured)) {
            return configured.trim();
        }
        String name = normalize(providerName);
        if (name == null) {
            name = DEFAULT_PROVIDER;
        }
        if ("ollama".equals(name)) {
            return orDefault(System.getProperty(LEGACY_OLLAMA_URL_KEY), OllamaProvider.DEFAULT_BASE_URL);
        }
        return requireDefaultBaseUrl(name);
    }

    private static String requireDefaultBaseUrl(String name) {
        return switch (name) {
            case "ollama" -> OllamaProvider.DEFAULT_BASE_URL;
            case "fastflowlm" -> FastFlowLmProvider.DEFAULT_BASE_URL;
            default -> throw unknownProvider(name);
        };
    }

    private static IllegalArgumentException unknownProvider(String providerName) {
        return new IllegalArgumentException(
                "Unknown LLM provider '" + providerName + "'. Expected 'ollama' or 'fastflowlm'.");
    }

    private static String lookup(String[] keys, String envKey) {
        for (String key : keys) {
            String value = System.getProperty(key);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        if (envKey != null) {
            String value = System.getenv(envKey);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static double decimal(String value, double fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring non-numeric configuration value '{}'; using {}.", value, fallback);
            return fallback;
        }
    }

    private static Integer integer(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring non-integer configuration value '{}'.", value);
            return null;
        }
    }

    private static boolean bool(String value, boolean fallback) {
        return isBlank(value) ? fallback : value.trim().equalsIgnoreCase("true");
    }

    private static String normalize(String providerName) {
        return isBlank(providerName) ? null : providerName.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}