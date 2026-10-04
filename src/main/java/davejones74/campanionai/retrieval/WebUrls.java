package davejones74.campanionai.retrieval;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * URL normalisation used to collapse the same page reported by several searches.
 */
public final class WebUrls {

    /**
     * Campaign parameters that identify a click, not a page. Two URLs differing only in these
     * are the same article, and a site that tracks campaigns will otherwise return the same
     * story once per parameter combination.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gclid", "fbclid", "msclkid", "dclid", "twclid", "igshid", "mc_cid", "mc_eid",
            "ref_src", "cmpid", "ito", "ns_campaign", "ns_mchannel", "ns_source");

    private WebUrls() {
    }

    /**
     * A stable key for "is this the same page?".
     *
     * <p>Lowercases the host, drops a {@code www.} prefix, drops the fragment, drops a trailing
     * slash, and drops campaign parameters. The path and any other query parameters are kept,
     * because on a news site the path is what distinguishes one article from another.
     *
     * <p>Falls back to the trimmed input when the URL cannot be parsed, so a malformed result
     * still deduplicates consistently against an identical malformed string.
     */
    public static String canonicalKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null) {
                return trimmed;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = meaningfulQuery(uri.getRawQuery());
            return host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (Exception e) {
            return trimmed;
        }
    }

    /** The lowercased host of a URL, or an empty string when there is none. */
    public static String host(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static String meaningfulQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        TreeMap<String, String> kept = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (TRACKING_PARAMS.contains(lowerName) || lowerName.startsWith("utm_")) {
                continue;
            }
            kept.put(name, value);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kept.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey());
            if (!e.getValue().isEmpty()) {
                sb.append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }
}