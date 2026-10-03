package davejones74.campanionai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the property/environment precedence that lets systemd's EnvironmentFile
 * supply the auth token without exposing it in {@code ps} output.
 */
class ConfigTest {

    private static final List<String> KEYS = List.of(
            "COMPANIONAI_HOST", "COMPANIONAI_PORT", "COMPANIONAI_AUTH_TOKEN",
            "COMPANIONAI_DATA_DIR", "COMPANIONAI_ALLOW_PRIVATE_FETCH",
            "COMPANIONAI_NO_SUCH_VAR");

    private final Set<String> injected = new HashSet<>();

    @AfterEach
    void restoreEnvironment() {
        for (String key : KEYS) {
            setEnvironment(key, null);
        }
        injected.clear();
        System.clearProperty("campanionai.host");
        System.clearProperty("campanionai.port");
        System.clearProperty("campanionai.authToken");
        System.clearProperty("campanionai.dataDir");
        System.clearProperty("campanionai.allowPrivateFetch");
    }

    /**
     * Sets an environment variable for the duration of the test, or skips the test when the
     * JVM refuses reflective access.
     *
     * <p>Both maps must be written: on Windows {@code System.getenv} is backed by
     * {@code theCaseInsensitiveEnvironment}, on Linux by {@code theEnvironment}.
     */
    private void withEnv(String key, String value) {
        assumeTrue(setEnvironment(key, value), "JVM refuses reflective environment mutation");
        injected.add(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environmentMap(Class<?> environment, String fieldName)
            throws ReflectiveOperationException {
        Field field = environment.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

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
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Test
    void propertyWinsOverEnvironment() {
        withEnv("COMPANIONAI_HOST", "from-env");
        System.setProperty("campanionai.host", "from-property");
        assertEquals("from-property", Config.string("campanionai.host", "COMPANIONAI_HOST", "fallback"));
    }

    @Test
    void environmentUsedWhenPropertyAbsent() {
        withEnv("COMPANIONAI_AUTH_TOKEN", "s3cret-from-env");
        assertEquals("s3cret-from-env",
                Config.string("campanionai.authToken", "COMPANIONAI_AUTH_TOKEN", null));
    }

    @Test
    void blankValuesAreTreatedAsAbsent() {
        withEnv("COMPANIONAI_HOST", "   ");
        assertEquals("10.0.0.1", Config.string("campanionai.host", "COMPANIONAI_HOST", "10.0.0.1"));
    }

    @Test
    void fallbackUsedWhenNeitherPresent() {
        assertEquals("0.0.0.0", Config.string("campanionai.host", "COMPANIONAI_NO_SUCH_VAR", "0.0.0.0"));
        assertEquals(8080, Config.integer("campanionai.port", "COMPANIONAI_NO_SUCH_VAR", 8080));
        assertFalse(Config.bool("campanionai.allowPrivateFetch", "COMPANIONAI_NO_SUCH_VAR", false));
    }

    @Test
    void absentAuthTokenIsNullNotEmptyString() {
        // Server treats null and blank identically, but returning null keeps the
        // "no filter installed" branch obvious rather than hiding it behind "".
        assertNull(Config.string("campanionai.authToken", "COMPANIONAI_NO_SUCH_VAR", null));
    }

    @Test
    void environmentIsTrimmed() {
        withEnv("COMPANIONAI_PORT", " 9090 ");
        assertEquals(9090, Config.integer("campanionai.port", "COMPANIONAI_PORT", 8080));
    }

    @Test
    void malformedIntegerFallsBackInsteadOfThrowing() {
        withEnv("COMPANIONAI_PORT", "not-a-number");
        // A typo in an environment file must not abort startup.
        assertEquals(8080, Config.integer("campanionai.port", "COMPANIONAI_PORT", 8080));
    }

    @Test
    void booleansAreCaseInsensitive() {
        withEnv("COMPANIONAI_ALLOW_PRIVATE_FETCH", "TRUE");
        assertTrue(Config.bool("campanionai.allowPrivateFetch", "COMPANIONAI_ALLOW_PRIVATE_FETCH", false));
        withEnv("COMPANIONAI_ALLOW_PRIVATE_FETCH", "False");
        assertFalse(Config.bool("campanionai.allowPrivateFetch", "COMPANIONAI_ALLOW_PRIVATE_FETCH", true));
    }

    @Test
    void unrecognisedBooleanFailsClosed() {
        withEnv("COMPANIONAI_ALLOW_PRIVATE_FETCH", "yes");
        // allowPrivateFetch is an SSRF control: an unparseable value must not enable it.
        assertFalse(Config.bool("campanionai.allowPrivateFetch", "COMPANIONAI_ALLOW_PRIVATE_FETCH", false));
    }

    @Test
    void nullEnvironmentKeyIsTolerated() {
        assertEquals("fallback", Config.string("campanionai.host", null, "fallback"));
    }

    @Test
    void dataDirResolvesFromEnvironment() {
        withEnv("COMPANIONAI_DATA_DIR", "/srv/companionai/data");
        assertEquals("/srv/companionai/data",
                Config.string("campanionai.dataDir", "COMPANIONAI_DATA_DIR", "./data"));
    }
}