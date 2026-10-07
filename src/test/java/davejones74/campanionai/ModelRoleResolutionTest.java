package davejones74.campanionai;

import davejones74.campanionai.llm.LlmProvider;
import davejones74.campanionai.llm.LlmProviderFactory;
import davejones74.campanionai.llm.ModelRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Role resolution: MAIN keeps its existing configuration, VISION inherits the
 * MAIN provider/base URL/temperature unless overridden, and the vision model
 * name defaults to qwen3vl-flash:4b.
 */
class ModelRoleResolutionTest {

    private final List<String> props = new ArrayList<>();

    private void set(String key, String value) {
        System.setProperty(key, value);
        props.add(key);
    }

    @AfterEach
    void tearDown() {
        for (String key : props) {
            System.clearProperty(key);
        }
        props.clear();
    }

    @Test
    void mainUsesExistingConfiguration() {
        set("companionai.llm.provider", "ollama");
        set("companionai.llm.model", "main-model");
        set("companionai.llm.baseUrl", "http://localhost:11434");

        LlmProvider main = LlmProviderFactory.fromSystemProperties(ModelRole.MAIN);

        assertEquals("ollama", main.providerName());
        assertEquals("main-model", main.model());
        assertEquals("http://localhost:11434", main.baseUrl());
    }

    @Test
    void visionInheritsMainProviderAndBaseUrlButDefaultsToTheVisionModel() {
        set("companionai.llm.provider", "fastflowlm");
        set("companionai.llm.model", "deepseek-r1-0528:8b");
        set("companionai.llm.baseUrl", "http://127.0.0.1:52625");

        LlmProvider vision = LlmProviderFactory.fromSystemProperties(ModelRole.VISION);

        assertEquals("fastflowlm", vision.providerName());
        assertEquals("http://127.0.0.1:52625", vision.baseUrl());
        assertEquals("qwen3vl-flash:4b", vision.model());
    }

    @Test
    void visionCanBeOverriddenToADifferentProviderAndModel() {
        set("companionai.llm.provider", "fastflowlm");
        set("companionai.llm.model", "deepseek-r1-0528:8b");
        set("companionai.llm.baseUrl", "http://127.0.0.1:52625");
        set("companionai.llm.vision.provider", "ollama");
        set("companionai.llm.vision.model", "qwen3-vl:4b");
        set("companionai.llm.vision.baseUrl", "http://localhost:11434");

        LlmProvider vision = LlmProviderFactory.fromSystemProperties(ModelRole.VISION);

        assertEquals("ollama", vision.providerName());
        assertEquals("qwen3-vl:4b", vision.model());
        assertEquals("http://localhost:11434", vision.baseUrl());
    }

    @Test
    void changingConfigurationChangesResolutionWithoutCodeChanges() {
        set("companionai.llm.provider", "ollama");
        set("companionai.llm.model", "main-a");
        set("companionai.llm.baseUrl", "http://localhost:11434");
        assertEquals("qwen3vl-flash:4b", LlmProviderFactory.fromSystemProperties(ModelRole.VISION).model());

        set("companionai.llm.vision.model", "qwen3-vl:4b");
        assertEquals("qwen3-vl:4b", LlmProviderFactory.fromSystemProperties(ModelRole.VISION).model());
    }
}
