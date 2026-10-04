package davejones74.campanionai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import davejones74.campanionai.FileRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

public final class ToolExecutor {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9._-]+");

    private final Path generatedDir;

    public ToolExecutor(Path dataDir) throws IOException {
        this.generatedDir = dataDir.resolve("generated");
        Files.createDirectories(generatedDir);
    }

    public ToolResult execute(JsonNode toolCall) throws IOException {
        if (toolCall == null) {
            return new ToolResult(List.of());
        }
        String name = toolCall.path("function").path("name").asText("");
        JsonNode args = toolCall.path("function").path("arguments");
        if (args.isMissingNode() || args.isNull()) {
            args = toolCall.path("arguments");
        }
        if ("create_file".equals(name)) {
            return new ToolResult(List.of(createFile(args)));
        }
        return new ToolResult(List.of());
    }

    public static final long MAX_CONTENT_BYTES = 1024 * 1024;
    private static final java.util.Set<String> ALLOWED_EXTENSIONS =
            java.util.Set.of(".md", ".txt", ".json", ".csv");

    public FileRef createFile(JsonNode args) throws IOException {
        String filename = args.path("filename").asText("");
        String mimeType = args.path("mimeType").asText("text/plain");
        String content = args.path("content").asText("");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new IOException("File too large (limit " + MAX_CONTENT_BYTES + " bytes)");
        }
        String sanitized = sanitize(filename);
        if (sanitized.isBlank()) {
            sanitized = "generated-" + Instant.now().toEpochMilli() + ".txt";
        }
        String extCheck = extension(sanitized).toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extCheck)) {
            throw new IOException("Unsupported file type: " + extCheck);
        }
        Path target = generatedDir.resolve(sanitized).normalize();
        if (!target.startsWith(generatedDir)) {
            throw new IOException("Invalid filename");
        }
        if (Files.exists(target)) {
            String base = baseName(sanitized);
            String ext = extension(sanitized);
            sanitized = base + "-" + Instant.now().toEpochMilli() + ext;
            target = generatedDir.resolve(sanitized);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        long size = Files.size(target);
        String url = "/api/files/" + sanitized;
        return new FileRef(sanitized, url, mimeType, size);
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.replace("\\", "/");
        int idx = n.lastIndexOf('/');
        if (idx >= 0) {
            n = n.substring(idx + 1);
        }
        idx = n.lastIndexOf('\\');
        if (idx >= 0) {
            n = n.substring(idx + 1);
        }
        if (!SAFE_NAME.matcher(n).matches()) {
            StringBuilder sb = new StringBuilder(n.length());
            for (int i = 0; i < n.length(); i++) {
                char c = n.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                    sb.append(c);
                } else {
                    sb.append('_');
                }
            }
            n = sb.toString();
        }
        if (n.length() > 255) {
            String ext = extension(n);
            String base = baseName(n);
            if (base.length() > 255 - ext.length()) {
                base = base.substring(0, 255 - ext.length());
            }
            n = base + ext;
        }
        return n;
    }

    private static String baseName(String n) {
        int dot = n.lastIndexOf('.');
        if (dot <= 0) {
            return n;
        }
        return n.substring(0, dot);
    }

    private static String extension(String n) {
        int dot = n.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return n.substring(dot);
    }
}
