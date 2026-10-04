package davejones74.campanionai.retrieval;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A website the user named in plain text, such as the {@code bbc.co.uk} in
 * "bbc.co.uk - give me all the stories about Huw Edwards".
 *
 * <p>The scope exists because the search engine must not be told the hostname as a topic. When
 * {@code news.bbc.co.uk} is sent as query text, "news" is matched as a word and the engine is
 * asked about news sites called bbc.co.uk, rather than being asked about news on that host.
 *
 * <p>Scope is deliberately narrowed, never widened:
 * <ul>
 *   <li>Only a bare host is recognised. A host that already has a scheme ({@code https://...})
 *       is a page to fetch, which is existing behaviour and must not change.</li>
 *   <li>The host is kept exactly as the user wrote it. {@code news.bbc.co.uk} stays
 *       {@code news.bbc.co.uk} and does not become {@code bbc.co.uk}.</li>
 *   <li>{@link #containsHost} matches the host itself and deeper subdomains only, which is what
 *       Tavily's {@code include_domains} does for a bare domain. It never accepts a sibling host,
 *       so {@code news.bbc.co.uk} cannot pull in {@code www.bbc.co.uk}.</li>
 *   <li>Too few results is not a reason to widen. See
 *       {@code RetrievalService} for how that is stated to the model.</li>
 * </ul>
 */
public record WebScope(String host, String remainder) {

    /**
     * A dotted name: at least two labels, a purely alphabetic top label of two or more
     * characters. The alphabetic final label is what separates a hostname from a version
     * ("3.6"), a date ("2024.10.01") or an abbreviation ("e.g.").
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?i)(?<![@/\\w.])((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,})(?![\\w-])");

    /** Leaves a leading {@code site:} operator behind when the host is removed. */
    private static final Pattern SITE_OPERATOR = Pattern.compile("(?i)\\bsite\\s*:\\s*");

    /**
     * Separators exposed by removing the host. "bbc.co.uk - stories about X" leaves a leading
     * dash, and a dangling dash would otherwise be sent to the engine as part of the topic.
     */
    private static final Pattern SEPARATORS = Pattern.compile("^[\\s\\-:,]+|[\\s\\-:,]+$");

    /**
     * Filenames that would otherwise parse as hosts. "summarise report.docx" must not scope a
     * search to the domain {@code docx}.
     */
    private static final Set<String> NOT_HOSTS = Set.of(
            "txt", "doc", "docx", "pdf", "md", "rtf", "csv", "tsv", "json", "xml", "yml", "yaml",
            "toml", "ini", "cfg", "conf", "log", "bak", "tmp", "sql", "db", "sqlite",
            "html", "htm", "xhtml", "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt",
            "kts", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "pl", "sh", "bash",
            "ps1", "bat", "cmd", "exe", "dll", "so", "dylib", "class", "jar", "war",
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico", "tif", "tiff",
            "zip", "tar", "gz", "tgz", "bz2", "7z", "rar", "mp3", "mp4", "wav", "avi", "mov",
            "ppt", "pptx", "xls", "xlsx", "odt", "ods");

    public WebScope {
        host = host == null ? null : host.toLowerCase(Locale.ROOT);
        remainder = remainder == null ? "" : remainder;
    }

    /**
     * Extracts the first host the user named, if any.
     *
     * <p>First match wins. A message naming two sites is ambiguous, and picking one silently
     * would be worse than picking the first in a documented, deterministic way.
     *
     * @return the scope with the remaining text, or empty when the message names no host
     */
    public static Optional<WebScope> extract(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CANDIDATE.matcher(input);
        while (m.find()) {
            String candidate = m.group(1).toLowerCase(Locale.ROOT);
            if (NOT_HOSTS.contains(topLabel(candidate))) {
                continue;
            }
            return Optional.of(new WebScope(candidate, without(input, m.start(1), m.end(1))));
        }
        return Optional.empty();
    }

    /**
     * Whether a URL belongs to this scope: the host itself, or a host below it.
     *
     * <p>Applied to every result before it reaches the prompt, so correctness of the scope does
     * not depend on the engine honouring {@code include_domains}.
     */
    public boolean containsHost(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank() || host == null) {
            return false;
        }
        String candidate = urlOrHost.toLowerCase(Locale.ROOT);
        int schemeAt = candidate.indexOf("://");
        if (schemeAt >= 0) {
            candidate = candidate.substring(schemeAt + 3);
        }
        candidate = candidate.split("[/?#]", 2)[0];
        int portAt = candidate.indexOf(':');
        if (portAt >= 0) {
            candidate = candidate.substring(0, portAt);
        }
        return candidate.equals(host) || candidate.endsWith("." + host);
    }

    private static String without(String input, int start, int end) {
        StringBuilder sb = new StringBuilder(input);
        sb.delete(start, end);
        String rest = SITE_OPERATOR.matcher(sb).replaceAll(" ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return SEPARATORS.matcher(rest).replaceAll("").trim();
    }

    private static String topLabel(String candidate) {
        int dot = candidate.lastIndexOf('.');
        return dot < 0 ? candidate : candidate.substring(dot + 1);
    }
}