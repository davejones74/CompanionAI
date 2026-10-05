package davejones74.campanionai.llm;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link LlmProviderFactory}'s two jobs: resolving configuration into the right provider,
 * and refusing to let an unverified vendor default pass as a configured value.
 *
 * <p>These tests assert factory behaviour only. They never contact a runtime, and they say
 * nothing about whether FastFlowLM honours the request shape its provider emits; that is covered
 * at the wire level by {@code FastFlowLmProviderTest} and by the measurements recorded in
 * {@code docs/X1Pro-FastFlowLM-Validation.md}.
 */
class LlmProviderFactoryTest {

    private static final List<String> PROPERTY_KEYS = List.of(
            "companionai.llm.provider",
            "companionai.llm.model",
            "companionai.llm.baseUrl",
            "companionai.llm.temperature",
            "companionai.llm.numCtx",
            "companionai.llm.think",
            "companionai.llm.maxTokens",
            "campanionai.llm.provider",
            "campanionai.llm.model",
            "campanionai.llm.baseUrl",
            "campanionai.llm.temperature",
            "campanionai.llm.think",
            "campanionai.llm.maxTokens",
            "campanionai.model",
            "campanionai.temperature",
            "campanionai.numCtx",
            "campanionai.ollamaUrl");

    private static final List<String> ENVIRONMENT_KEYS = List.of(
            "LLM_PROVIDER", "LLM_MODEL", "LLM_BASE_URL", "LLM_TEMPERATURE", "LLM_NUM_CTX", "LLM_THINK",
            "LLM_MAX_TOKENS");

    private final Map<String, String> savedProperties = new HashMap<>();
    private final Map<String, String> savedEnvironment = new HashMap<>();

    private WarnCapture warnings;

    @AfterEach
    void restoreConfiguration() {
        for (String key : PROPERTY_KEYS) {
            String previous = savedProperties.remove(key);
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
        for (String key : ENVIRONMENT_KEYS) {
            setEnvironment(key, savedEnvironment.remove(key));
        }
        if (warnings != null) {
            warnings.detach();
            warnings = null;
        }
    }

    /**
     * Clears every key this factory reads, both properties and environment variables, so each test
     * starts from the documented defaults instead of inheriting the developer's shell.
     */
    private void clearConfiguration() {
        for (String key : PROPERTY_KEYS) {
            savedProperties.putIfAbsent(key, System.getProperty(key));
            System.clearProperty(key);
        }
        for (String key : ENVIRONMENT_KEYS) {
            savedEnvironment.putIfAbsent(key, System.getenv(key));
            setEnvironment(key, null);
        }
        warnings = WarnCapture.attachTo(LlmProviderFactory.class);
    }

    private String warnings() {
        return warnings.text();
    }

    @Test
    void fallsBackToTheDocumentedDefaults() {
        clearConfiguration();

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertInstanceOf(OllamaProvider.class, provider);
        assertEquals(LlmProviderFactory.DEFAULT_PROVIDER, provider.providerName());
        assertEquals(LlmProviderFactory.DEFAULT_MODEL, provider.model());
        assertEquals(LlmProviderFactory.DEFAULT_TEMPERATURE, provider.temperature(), 1e-9);
        assertEquals(OllamaProvider.DEFAULT_BASE_URL, provider.baseUrl());
    }

    @Test
    void prefersTheCorrectlySpelledKeyOverTheLegacyMisspelling() {
        clearConfiguration();
        System.setProperty("companionai.llm.model", "correctly-spelled");
        System.setProperty("campanionai.llm.model", "legacy-misspelled");

        assertEquals("correctly-spelled", LlmProviderFactory.fromSystemProperties().model());
    }

    /**
     * The legacy spelling covers two shapes: the {@code *.llm.*} keys that arrived with this
     * package under the misspelt namespace, and the older top-level {@code campanionai.*}
     * keys. Both must keep working, because existing launch scripts set them.
     */
    @Test
    void stillHonoursTheLegacyMisspelledKeys() {
        clearConfiguration();
        System.setProperty("campanionai.llm.provider", "ollama");
        System.setProperty("campanionai.llm.baseUrl", "http://legacy.example:11434");
        System.setProperty("campanionai.model", "legacy-model");
        System.setProperty("campanionai.temperature", "0.3");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertEquals("legacy-model", provider.model());
        assertEquals(0.3, provider.temperature(), 1e-9);
        assertEquals("http://legacy.example:11434", provider.baseUrl());
    }

    @Test
    void keepsThePreExistingOllamaKeysWorking() {
        clearConfiguration();
        System.setProperty("campanionai.model", "gemma4:12b");
        System.setProperty("campanionai.temperature", "0.25");
        System.setProperty("campanionai.numCtx", "8192");
        System.setProperty("campanionai.ollamaUrl", "http://ollama.example:11434");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertEquals("gemma4:12b", provider.model());
        assertEquals(0.25, provider.temperature(), 1e-9);
        assertEquals("http://ollama.example:11434", provider.baseUrl());
        assertTrue(provider.capabilities().has(LlmCapability.PER_REQUEST_CONTEXT_LENGTH));
    }

    @Test
    void refusesToGuessAtAnUnknownProvider() {
        clearConfiguration();
        System.setProperty("companionai.llm.provider", "llama.cpp");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LlmProviderFactory.fromSystemProperties());

        assertTrue(thrown.getMessage().contains("llama.cpp"), thrown.getMessage());
    }

    @Test
    void warnsWhenFastFlowLmFallsBackToItsUnverifiedDefaultBaseUrl() {
        clearConfiguration();
        System.setProperty("companionai.llm.provider", "fastflowlm");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertInstanceOf(FastFlowLmProvider.class, provider);
        assertEquals(FastFlowLmProvider.DEFAULT_BASE_URL, provider.baseUrl());
        assertTrue(warnings().contains("No base URL configured"), warnings());
        assertTrue(warnings().contains("NOT validated against real hardware"), warnings());
    }

    @Test
    void staysSilentWhenFastFlowLmHasAnExplicitBaseUrl() {
        clearConfiguration();
        System.setProperty("companionai.llm.provider", "fastflowlm");
        System.setProperty("companionai.llm.baseUrl", "http://127.0.0.1:52625");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertEquals("http://127.0.0.1:52625", provider.baseUrl());
        assertFalse(warnings().contains("No base URL configured"), warnings());
    }

    @Test
    void staysSilentForOllamaBecauseItsDefaultIsLongStanding() {
        clearConfiguration();

        LlmProviderFactory.fromSystemProperties();

        assertFalse(warnings().contains("No base URL configured"), warnings());
    }

    @Test
    void treatsTheLegacyOllamaUrlAsExplicitConfiguration() {
        clearConfiguration();
        System.setProperty("campanionai.ollamaUrl", "http://ollama.example:11434");

        LlmProviderFactory.fromSystemProperties();

        assertFalse(warnings().contains("No base URL configured"), warnings());
    }

    @Test
    void warnsThatFastFlowLmIgnoresAPerRequestContextLength() {
        clearConfiguration();
        System.setProperty("companionai.llm.provider", "fastflowlm");
        System.setProperty("companionai.llm.baseUrl", "http://127.0.0.1:52625");
        System.setProperty("companionai.llm.numCtx", "8192");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertFalse(provider.capabilities().has(LlmCapability.PER_REQUEST_CONTEXT_LENGTH));
        assertTrue(warnings().contains("does not support a per-request context length"), warnings());
    }

    @Test
    void ignoresANonNumericTemperatureRatherThanFailingStartup() {
        clearConfiguration();
        System.setProperty("companionai.llm.temperature", "warm");

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertEquals(LlmProviderFactory.DEFAULT_TEMPERATURE, provider.temperature(), 1e-9);
        assertTrue(warnings().contains("Ignoring non-numeric configuration value"), warnings());
    }

    @Test
    void defaultsMaxTokensTo1024WhenUnset() {
        clearConfiguration();

        assertEquals(1024, LlmProviderFactory.fromSystemProperties().maxTokens());
    }

    @Test
    void honoursAConfiguredMaxTokens() {
        clearConfiguration();
        System.setProperty("companionai.llm.maxTokens", "2048");

        assertEquals(2048, LlmProviderFactory.fromSystemProperties().maxTokens());
    }

    @Test
    void readsMaxTokensFromTheEnvironment() {
        clearConfiguration();
        assertTrue(setEnvironment("LLM_MAX_TOKENS", "512"), environmentUnavailable());

        assertEquals(512, LlmProviderFactory.fromSystemProperties().maxTokens());
    }

    @Test
    void fallsBackTo1024ForANonNumericMaxTokens() {
        clearConfiguration();
        System.setProperty("companionai.llm.maxTokens", "abc");

        assertEquals(1024, LlmProviderFactory.fromSystemProperties().maxTokens());
        assertTrue(warnings().contains("Ignoring non-integer configuration value"), warnings());
    }

    @Test
    void fallsBackTo1024ForANonPositiveMaxTokens() {
        clearConfiguration();
        System.setProperty("companionai.llm.maxTokens", "0");

        assertEquals(1024, LlmProviderFactory.fromSystemProperties().maxTokens());
        assertTrue(warnings().contains("Ignoring non-positive configuration value"), warnings());
    }

    @Test
    void readsEnvironmentVariablesWhenNoPropertyIsSet() {
        clearConfiguration();
        // Environment variables are the deployment path: /etc/companionai/companionai.env.
        // A JVM that cannot mutate its own environment would leave that path untested, so
        // this fails loudly rather than skipping.
        assertTrue(setEnvironment("LLM_PROVIDER", "ollama"), environmentUnavailable());
        assertTrue(setEnvironment("LLM_MODEL", "env-model"), environmentUnavailable());
        assertTrue(setEnvironment("LLM_TEMPERATURE", "0.42"), environmentUnavailable());

        LlmProvider provider = LlmProviderFactory.fromSystemProperties();

        assertEquals("env-model", provider.model());
        assertEquals(0.42, provider.temperature(), 1e-9);
    }

    /** Last reflective failure, kept so a broken environment can explain itself. */
    private static String environmentFailure = "none";

    /**
     * Reflectively writes to the process environment.
     *
     * <p>Returns {@code false} and records the reason when the JVM refuses, which happens
     * without {@code --add-opens java.base/java.lang=ALL-UNNAMED}. The reason is surfaced in
     * the assertion message: silently skipping turned a missing JVM flag into a test
     * failure reading "expected env-model but was qwen3.6:27b", which points at the
     * factory rather than at the build.
     */
    private static boolean setEnvironment(String key, String value) {
        try {
            Class<?> environment = Class.forName("java.lang.ProcessEnvironment");
            for (String fieldName : List.of("theEnvironment", "theCaseInsensitiveEnvironment")) {
                Map<String, String> map = environmentMap(environment, fieldName);
                if (map == null) {
                    continue;
                }
                if (value == null) {
                    map.remove(key);
                } else {
                    map.put(key, value);
                }
            }
            environmentFailure = "none";
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            environmentFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    private static String environmentUnavailable() {
        return "Process environment is not mutable in this JVM (" + environmentFailure
                + "). The test JVM needs --add-opens java.base/java.lang=ALL-UNNAMED; check"
                + " tasks.named('test') { jvmArgs ... } in build.gradle.";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environmentMap(Class<?> environment, String fieldName)
            throws ReflectiveOperationException {
        var field = environment.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    /**
     * Collects the warnings a class emits, so a configuration mistake is asserted rather than
     * eyeballed in console output.
     */
    private static final class WarnCapture extends AbstractAppender {

        private final List<String> messages = new ArrayList<>();
        private final LoggerConfig loggerConfig;

        private WarnCapture(LoggerConfig loggerConfig) {
            super(WarnCapture.class.getSimpleName(), null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
            this.loggerConfig = loggerConfig;
        }

        static WarnCapture attachTo(Class<?> owner) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            LoggerConfig loggerConfig = configuration.getLoggerConfig(owner.getName());
            WarnCapture capture = new WarnCapture(loggerConfig);
            capture.start();
            loggerConfig.addAppender(capture, Level.WARN, null);
            context.updateLoggers();
            return capture;
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
        }

        String text() {
            return String.join(System.lineSeparator(), messages);
        }

        void detach() {
            loggerConfig.removeAppender(getName());
            stop();
            ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        }
    }
}