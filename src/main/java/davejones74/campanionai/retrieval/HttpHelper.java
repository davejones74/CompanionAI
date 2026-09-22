package davejones74.campanionai.retrieval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

final class HttpHelper {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpHelper() {
    }

    static String jsonGet(String url, Duration timeout) throws RetrievalException {
        return jsonGet(url, Map.of(), timeout);
    }

    static String jsonGet(String url, Map<String, String> headers, Duration timeout) throws RetrievalException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json");
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    static String jsonPost(String url, Map<String, String> headers, String jsonBody, Duration timeout)
            throws RetrievalException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach((k, v) -> {
            if (v != null && !v.isBlank()) builder.header(k, v);
        });
    }

    private static String send(HttpRequest request) throws RetrievalException {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();
            if (status < 200 || status >= 300) {
                String detail = body == null ? "" : " " + body.substring(0, Math.min(body.length(), 200));
                throw new RetrievalException("External service returned HTTP " + status + detail);
            }
            return body == null ? "" : body;
        } catch (RetrievalException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetrievalException("Request interrupted", "Current external information could not be retrieved.", e);
        } catch (Exception e) {
            throw new RetrievalException("Failed to contact external service: " + e.getMessage(),
                    "Current external information could not be retrieved.", e);
        }
    }
}