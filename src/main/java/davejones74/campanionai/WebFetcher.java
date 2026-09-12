package davejones74.campanionai;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class WebFetcher {
    public record Page(String url, String title, String text) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final long maxBytes;
    private final boolean allowPrivate;

    public WebFetcher(long maxBytes, boolean allowPrivate) {
        this.maxBytes = maxBytes;
        this.allowPrivate = allowPrivate;
    }

    public boolean isAllowed(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            if (allowPrivate || uri.getHost() == null) return true;
            return !isPrivateHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPrivateHost(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    public Page fetch(String rawUrl) throws IOException {
        URI uri = URI.create(rawUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CompanionAI/1.0 (knowledge-base fetcher)")
                .header("Accept", "text/html,text/plain,application/json,*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + rawUrl, e);
        }
        int status = response.statusCode();
        try (InputStream in = response.body()) {
            if (status != 200) {
                throw new IOException("HTTP " + status + " while fetching " + rawUrl);
            }
            String ctype = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            String body = drain(in);
            if (body.isEmpty()) {
                return new Page(rawUrl, "", "");
            }
            if (ctype.contains("text/html") || ctype.contains("application/xhtml")) {
                Document doc = Jsoup.parse(body, rawUrl);
                String text = articleText(doc);
                return new Page(rawUrl, doc.title().trim(), text);
            }
            return new Page(rawUrl, "", body);
        }
    }

    private static String articleText(Document doc) {
        Element main = null;
        Elements candidates = doc.select("article, main, [role=main]");
        for (Element el : candidates) {
            if (el.text().length() > (main == null ? 0 : main.text().length())) {
                main = el;
            }
        }
        Document target = main == null ? doc : main.ownerDocument();
        Element root = main == null ? target.body() : main;
        Element copy = root.clone();
        copy.select("script, style, noscript, nav, aside, form, header, footer, iframe, svg, button").remove();
        String text = copy.text().trim();
        return text.isEmpty() ? root.text().trim() : text;
    }

    private String drain(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        long total = 0;
        int n;
        long limit = maxBytes;
        while ((n = in.readNBytes(buffer, 0, (int) Math.min(buffer.length, limit + 1 - total))) > 0) {
            sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            total += n;
            if (total > limit) {
                int cut = sb.length();
                while (cut > 0 && !Character.isWhitespace(sb.charAt(cut - 1))) cut--;
                sb.setLength(cut > 0 ? cut : sb.length());
                break;
            }
        }
        return sb.toString();
    }
}