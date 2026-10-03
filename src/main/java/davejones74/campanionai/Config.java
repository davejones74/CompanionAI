package davejones74.campanionai;

/**
 * Configuration lookup with the same precedence already used by
 * {@code LlmProviderFactory}: system property, then environment variable, then default.
 *
 * <p>The environment tier exists so that systemd's {@code EnvironmentFile=} can supply
 * secrets. A {@code -D} argument is visible to every user on the host via {@code ps -ef},
 * which makes {@code campanionai.authToken} unsafe to pass that way on a machine with
 * more than one account. Environment variables are readable only through
 * {@code /proc/<pid>/environ}, which is restricted to the process owner and root.
 *
 * <p>Property names keep their historical misspelling ({@code campanionai.*}) so existing
 * command lines and start scripts continue to work unchanged.
 */
public final class Config {

    private Config() {
    }

    /** Returns the property, else the environment variable, else {@code fallback}. */
    public static String string(String key, String envKey, String fallback) {
        String value = System.getProperty(key);
        if (isBlank(value)) {
            value = envKey == null ? null : System.getenv(envKey);
        }
        return isBlank(value) ? fallback : value.trim();
    }

    /**
     * Integer lookup that degrades to {@code fallback} rather than throwing.
     *
     * <p>{@code Integer.getInteger} throws {@code NumberFormatException} on a malformed
     * value, which would abort startup from a typo in an environment file.
     */
    public static int integer(String key, String envKey, int fallback) {
        String raw = string(key, envKey, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Boolean lookup accepting only {@code true}/{@code false} case-insensitively.
     *
     * <p>Anything unrecognised is {@code fallback}. For {@code allowPrivateFetch} that
     * matters: a misspelt value must fail closed, not open.
     */
    public static boolean bool(String key, String envKey, boolean fallback) {
        String raw = string(key, envKey, null);
        if (raw == null) {
            return fallback;
        }
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}