package davejones74.campanionai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@MultipartConfig(maxFileSize = 10 * 1024 * 1024, maxRequestSize = 12 * 1024 * 1024)
public class ModelServlet extends HttpServlet {
    private final ChatRules chat = new ChatRules();
    private final ObjectMapper json = new ObjectMapper();
    private LlmClient llm;
    private Path dataDir;

    private final String model = System.getProperty("campanionai.model", "qwen3.6:27b");
    private final String ollamaUrl = System.getProperty("campanionai.ollamaUrl", "http://localhost:11434");
    private final int maxChunksPerDoc = Integer.getInteger("campanionai.maxDocs", 3);
    private final int chunkTokens = Integer.getInteger("campanionai.chunkTokens", 1500);
    private final int chunkOverlap = Integer.getInteger("campanionai.chunkOverlap", 200);
    private final int maxContextTokens = Integer.getInteger("campanionai.maxContextTokens", 20000);
    private final int historyTokens = Integer.getInteger("campanionai.historyTokens", 8000);
    private final int maxHistoryMessages = Integer.getInteger("campanionai.historyMessages", 40);
    private final int maxUrlsPerMessage = Integer.getInteger("campanionai.maxUrlsPerMessage", 1);
    private final long maxFetchBytes = Long.getLong("campanionai.maxFetchBytes", 2L * 1024 * 1024);
    private final boolean allowPrivateFetch = Boolean.getBoolean("campanionai.allowPrivateFetch");
    private final double temperature = System.getProperty("campanionai.temperature") == null
            ? 0.7
            : Double.parseDouble(System.getProperty("campanionai.temperature"));

    private final WebFetcher fetcher = new WebFetcher(maxFetchBytes, allowPrivateFetch);
    private final UsageStats stats = new UsageStats();

    private final ReadWriteLock docsLock = new ReentrantReadWriteLock();
    private List<Doc> docs = new ArrayList<>();
    private List<Chunk> chunks = new ArrayList<>();
    private final Map<String, String> urlToFilename = new HashMap<>();
    private final List<LlmClient.ChatMessage> history = new ArrayList<>();

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>()\"']+");

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(ModelServlet.class);

    private record Doc(String filename, String content, String url) {
    }

    private record Chunk(Doc doc, int part, int parts, String text) {
    }

    private record ChunkScore(Chunk chunk, int score) {
    }

    private record ChatResult(String reply, boolean offline) {
    }

    @Override
    public void init() {
        try {
            String base = System.getProperty("campanionai.dataDir",
                    System.getProperty("user.dir") + File.separator + "data");
            dataDir = Path.of(base).toAbsolutePath();
            Files.createDirectories(dataDir);
            llm = new LlmClient(model, ollamaUrl, temperature);
            reloadDocuments();
            LOG.info("Knowledge base ready at {}. Loaded {} document(s).", dataDir, docCount());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise document store", e);
        }
    }

    private void reloadDocuments() throws IOException {
        List<Doc> loaded = new ArrayList<>();
        Map<String, String> urlMap = new HashMap<>();
        try (var stream = Files.walk(dataDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    String text = DocumentReader.extract(p.getFileName().toString(), in);
                    if (text.isBlank()) continue;
                    String url = parseUrlHeader(text);
                    String name = p.getFileName().toString();
                    if (url != null && p.getParent() != null && p.getParent().getFileName() != null
                            && p.getParent().getFileName().toString().equals("urls")) {
                        name = "URL " + url;
                    }
                    loaded.add(new Doc(name, text, url));
                    if (url != null) urlMap.put(url, name);
                }
            }
        }
        List<Chunk> chunked = new ArrayList<>();
        for (Doc d : loaded) chunked.addAll(chunk(d));
        docsLock.writeLock().lock();
        try {
            docs = new ArrayList<>(loaded);
            chunks = chunked;
            urlToFilename.clear();
            urlToFilename.putAll(urlMap);
        } finally {
            docsLock.writeLock().unlock();
        }
        LOG.info("Reloaded knowledge base: {} document(s) - {}", loaded.size(),
                loaded.stream().map(Doc::filename).sorted().toList());
    }

    private static String parseUrlHeader(String text) {
        if (text == null || !text.startsWith("# URL: ")) return null;
        int end = text.indexOf('\n');
        return text.substring(7, end < 0 ? text.length() : end).trim();
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "am",
            "what", "where", "when", "who", "which", "why", "how", "do", "does", "did",
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
            "to", "of", "in", "on", "at", "for", "from", "with", "by", "as", "that", "this",
            "these", "those", "and", "or", "but", "not", "no", "yes", "please", "can",
            "could", "would", "should", "will", "shall", "may", "might", "about", "into",
            "than", "then", "there", "here", "my", "your", "his", "its", "our", "their",
            "has", "have", "had", "if", "so", "also", "some", "any", "all", "just",
            "tell", "ask", "give", "want", "need");

    private static int estimateTokens(String s) {
        return (s.length() + 3) / 4;
    }

    private List<Chunk> chunk(Doc doc) {
        String content = doc.content();
        int totalTokens = estimateTokens(content);
        if (totalTokens <= chunkTokens) {
            return List.of(new Chunk(doc, 1, 1, content));
        }
        int chunkChars = Math.max(256, chunkTokens * 4);
        int stepChars = Math.max(128, (chunkTokens - chunkOverlap) * 4);
        List<Chunk> out = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + chunkChars);
            out.add(new Chunk(doc, out.size() + 1, 1, content.substring(start, end)));
            if (end >= content.length()) break;
            start += stepChars;
        }
        int parts = out.size();
        List<Chunk> fixed = new ArrayList<>(out.size());
        for (Chunk c : out) fixed.add(new Chunk(c.doc(), c.part(), parts, c.text()));
        return fixed;
    }

    private static List<String> tokenize(String message) {
        List<String> out = new ArrayList<>();
        for (String t : message.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /**
     * Picks the chunks most relevant to {@code message}, subject to an overall
     * token budget, so only a small slice of the knowledge base reaches the
     * model. Chunks belonging to freshly fetched URLs are injected first.
     */
    private List<Chunk> selectContext(String message, List<String> forcedUrls) {
        docsLock.readLock().lock();
        try {
            if (chunks.isEmpty()) return List.of();
            List<String> tokens = tokenize(message);
            List<Chunk> selected = new ArrayList<>();
            int used = 0;

            if (forcedUrls != null) {
                for (String url : forcedUrls) {
                    for (Chunk c : chunks) {
                        if (url.equals(c.doc().url())) {
                            int t = estimateTokens(c.text());
                            if (used + t > maxContextTokens) break;
                            selected.add(c);
                            used += t;
                        }
                    }
                }
            }

            List<ChunkScore> scored = new ArrayList<>();
            for (Chunk c : chunks) {
                if (selected.contains(c)) continue;
                int score = scoreChunk(c, tokens);
                if (score > 0) scored.add(new ChunkScore(c, score));
            }
            scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
            Map<String, Integer> perDoc = new HashMap<>();
            for (ChunkScore cs : scored) {
                Chunk c = cs.chunk();
                if (perDoc.getOrDefault(c.doc().filename(), 0) >= maxChunksPerDoc) continue;
                int t = estimateTokens(c.text());
                if (used + t > maxContextTokens) continue;
                selected.add(c);
                used += t;
                perDoc.merge(c.doc().filename(), 1, Integer::sum);
            }

            if (selected.isEmpty() && !chunks.isEmpty()) {
                Chunk first = chunks.get(0);
                if (estimateTokens(first.text()) <= maxContextTokens) selected.add(first);
            }
            if (LOG.isDebugEnabled() && !selected.isEmpty()) {
                LOG.debug("Context: {} chunks ~{} tokens", selected.size(), used);
            }
            return selected;
        } finally {
            docsLock.readLock().unlock();
        }
    }

    private int scoreChunk(Chunk chunk, List<String> tokens) {
        String low = chunk.text().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String tok : tokens) {
            if (STOPWORDS.contains(tok) || tok.length() < 2) continue;
            if (chunk.doc().filename().toLowerCase(Locale.ROOT).contains(tok)) score += 3;
            score += Math.min(countOccurrences(low, tok), 10);
        }
        return score;
    }

    private String systemPrompt(List<Chunk> used) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are CompanionAI, a friendly and helpful chat assistant. ")
          .append("You have a knowledge base of documents provided below. ")
          .append("Use them to answer the user's questions where relevant, but keep replies natural and conversational.");
        if (used.isEmpty()) {
            sb.append("\n\n(No documents in the knowledge base were relevant to this question.)");
        } else {
            sb.append("\n\nKnowledge base context:\n");
            for (Chunk c : used) {
                sb.append("--- Document: ").append(c.doc().filename());
                if (used.size() > 1 && c.parts() > 1) {
                    sb.append(" (part ").append(c.part()).append('/').append(c.parts()).append(')');
                }
                sb.append(" ---\n").append(c.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private List<String> findUrls(String message) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(message);
        while (m.find()) {
            String u = m.group().replaceAll("[.,;:!?]+$", "");
            if (!urls.contains(u)) urls.add(u);
        }
        if (urls.size() > maxUrlsPerMessage) {
            return new ArrayList<>(urls.subList(0, maxUrlsPerMessage));
        }
        return urls;
    }

    private boolean knowUrl(String url) {
        docsLock.readLock().lock();
        try {
            return urlToFilename.containsKey(url);
        } finally {
            docsLock.readLock().unlock();
        }
    }

    private void ensureFetched(String rawUrl) throws IOException {
        if (knowUrl(rawUrl)) return;
        if (!fetcher.isAllowed(rawUrl)) {
            throw new IOException("URL not allowed (must be http/https and non-private): " + rawUrl);
        }
        stats.recordFetch();
        WebFetcher.Page page;
        try {
            page = fetcher.fetch(rawUrl);
        } catch (IOException e) {
            stats.recordFetchError();
            throw e;
        }
        if (page.text().isBlank()) {
            stats.recordFetchError();
            throw new IOException("No readable text found at " + rawUrl);
        }
        Path dir = dataDir.resolve("urls");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("# URL: ").append(rawUrl).append('\n')
          .append("# Title: ").append(page.title().replace('\n', ' ').trim()).append('\n')
          .append("# Fetched: ").append(Instant.now()).append("\n\n")
          .append(page.text()).append('\n');
        Path target = dir.resolve(sha1(rawUrl) + ".txt");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        reloadDocuments();
        LOG.info("Fetched and stored: {}", rawUrl);
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<LlmClient.ChatMessage> buildMessages(String system, String input) {
        synchronized (history) {
            List<LlmClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new LlmClient.ChatMessage("system", system));
            messages.addAll(history);
            messages.add(new LlmClient.ChatMessage("user", input));
            return messages;
        }
    }

    private void appendHistory(String user, String assistant) {
        synchronized (history) {
            history.add(new LlmClient.ChatMessage("user", user));
            history.add(new LlmClient.ChatMessage("assistant", assistant));
            while (history.size() > 2) {
                int total = 0;
                for (LlmClient.ChatMessage m : history) total += estimateTokens(m.content());
                if (total <= historyTokens && history.size() <= maxHistoryMessages) break;
                history.remove(0);
                history.remove(0);
            }
        }
    }

    private void recordLatency(long startNanos, long outTokens) {
        stats.recordLatency((System.nanoTime() - startNanos) / 1_000_000L, outTokens);
    }

    private ChatResult respond(String input) {
        stats.recordStart();
        long t0 = System.nanoTime();
        if (input == null || input.trim().isEmpty()) {
            return new ChatResult("Please type something first.", false);
        }
        try {
            List<String> urls = new ArrayList<>();
            for (String u : findUrls(input)) {
                try {
                    ensureFetched(u);
                    urls.add(u);
                } catch (IOException e) {
                    return new ChatResult("Couldn't fetch " + u + ": " + e.getMessage(), false);
                }
            }
            List<Chunk> ctx = selectContext(input, urls);
            String system = systemPrompt(ctx);
            List<LlmClient.ChatMessage> messages = buildMessages(system, input);
            stats.addInputTokens(estimateTokens(system) + estimateTokens(input));
            String reply = llm.chat(messages);
            stats.addOutputTokens(estimateTokens(reply));
            stats.recordJsonReply();
            recordLatency(t0, estimateTokens(reply));
            appendHistory(input, reply);
            return new ChatResult(reply, false);
        } catch (LlmClient.LlmException e) {
            stats.recordOffline();
            String fallback = chat.reply(input);
            if (fallback != null) {
                String reply = fallback + "\n\n[LLM unavailable - offline reply]";
                appendHistory(input, reply);
                return new ChatResult(reply, true);
            }
            return new ChatResult("I couldn't reach the language model right now: " + e.getMessage(), true);
        }
    }

    private void handleStream(HttpServletRequest req, HttpServletResponse resp) {
        String input;
        PrintWriter out;
        try {
            JsonNode body = json.readTree(req.getInputStream());
            input = body.path("message").asText("");
            resp.setContentType("text/event-stream; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("X-Accel-Buffering", "no");
            resp.setBufferSize(0);
            out = resp.getWriter();
        } catch (IOException e) {
            return;
        }
        try {
            if (input.trim().isEmpty()) {
                writeEvent(out, Map.of("error", "Please type something first."));
                writeDone(out, false);
                return;
            }
            stats.recordStart();
            long t0 = System.nanoTime();
            List<String> urls = new ArrayList<>();
            for (String u : findUrls(input)) {
                writeEvent(out, Map.of("status", "Fetching " + u + "..."));
                try {
                    ensureFetched(u);
                    urls.add(u);
                } catch (IOException e) {
                    LOG.warn("URL fetch failed: {}", String.valueOf(e.getMessage()));
                    writeEvent(out, Map.of("error", "Couldn't fetch " + u + ": " + e.getMessage()));
                    writeDone(out, false);
                    return;
                }
            }
            List<Chunk> ctx = selectContext(input, urls);
            String system = systemPrompt(ctx);
            List<LlmClient.ChatMessage> messages = buildMessages(system, input);
            stats.addInputTokens(estimateTokens(system) + estimateTokens(input));
            StringBuilder replyBuilder = new StringBuilder();
            boolean offline = false;
            try {
                llm.chatStream(messages, delta -> {
                    replyBuilder.append(delta);
                    try {
                        writeEvent(out, Map.of("delta", delta));
                    } catch (IOException e) {
                        throw new StreamAbort(e);
                    }
                });
            } catch (StreamAbort e) {
                LOG.debug("Stream aborted (client disconnected).");
                return;
            } catch (LlmClient.LlmException e) {
                LOG.warn("LLM stream failed: {}", e.getMessage());
                stats.recordOffline();
                String fallback = chat.reply(input);
                if (fallback != null) {
                    offline = true;
                    replyBuilder.append(fallback).append("\n\n[LLM unavailable - offline reply]");
                    writeEvent(out, Map.of("delta", fallback, "offline", true));
                } else {
                    writeEvent(out, Map.of("error", "I couldn't reach the language model right now: " + e.getMessage()));
                    writeDone(out, true);
                    return;
                }
            }
            String reply = replyBuilder.toString();
            appendHistory(input, reply);
            stats.addOutputTokens(estimateTokens(reply));
            stats.recordStreamed();
            recordLatency(t0, estimateTokens(reply));
            writeDone(out, offline);
        } catch (StreamAbort e) {
            LOG.debug("Stream aborted (client disconnected).");
        } catch (Exception e) {
            LOG.warn("Stream handler error: {}", String.valueOf(e.getMessage()));
            try {
                writeEvent(out, Map.of("error", String.valueOf(e.getMessage())));
                writeDone(out, true);
            } catch (IOException ignored) {
            }
        }
    }

    private void writeEvent(PrintWriter out, Map<String, ?> fields) throws IOException {
        out.write("data: " + json.writeValueAsString(fields) + "\n\n");
        out.flush();
    }

    private void writeDone(PrintWriter out, boolean offline) throws IOException {
        writeEvent(out, Map.of("done", true, "offline", offline));
    }

    private static final class StreamAbort extends RuntimeException {
        StreamAbort(Throwable cause) {
            super(cause);
        }
    }

    private int docCount() {
        docsLock.readLock().lock();
        try {
            return docs.size();
        } finally {
            docsLock.readLock().unlock();
        }
    }

    private void saveUpload(String filename, Part part) throws IOException {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = dataDir.resolve(safe);
        if (Files.exists(target)) {
            target = dataDir.resolve(System.currentTimeMillis() + "_" + safe);
        }
        try (InputStream in = part.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (path.endsWith("/api/stats")) {
            resp.setContentType("application/json; charset=UTF-8");
            resp.setHeader("Cache-Control", "no-store");
            resp.getWriter().write(stats.toJson());
            return;
        }
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(page());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        String path = req.getRequestURI();
        try {
            if (path.endsWith("/api/chat/stream")) {
                resp.setStatus(200);
                handleStream(req, resp);
            } else if (path.endsWith("/api/chat")) {
                JsonNode body = json.readTree(req.getInputStream());
                ChatResult result = respond(body.path("message").asText(""));
                resp.getWriter().write(json.writeValueAsString(Map.of(
                        "reply", result.reply(), "offline", result.offline())));
            } else if (path.endsWith("/upload")) {
                Part doc = req.getPart("doc");
                String filename = "unknown";
                if (doc != null && doc.getSize() > 0) {
                    filename = Path.of(doc.getSubmittedFileName()).getFileName().toString();
                    saveUpload(filename, doc);
                    reloadDocuments();
                }
                resp.getWriter().write(json.writeValueAsString(Map.of(
                        "ok", true,
                        "docs", docCount(),
                        "message", "Uploaded '" + filename + "'. Knowledge base now has " + docCount() + " document(s).")));
            } else {
                boolean isMultipart = req.getContentType() != null
                        && req.getContentType().toLowerCase().startsWith("multipart/");
                if (isMultipart && req.getPart("doc") != null && req.getPart("doc").getSize() > 0) {
                    Part doc = req.getPart("doc");
                    saveUpload(Path.of(doc.getSubmittedFileName()).getFileName().toString(), doc);
                    reloadDocuments();
                    resp.getWriter().write(json.writeValueAsString(Map.of(
                            "ok", true, "docs", docCount(),
                            "message", "Knowledge base now has " + docCount() + " document(s).")));
                } else {
                    ChatResult result = respond(req.getParameter("text"));
                    resp.getWriter().write(json.writeValueAsString(Map.of(
                            "reply", result.reply(), "offline", result.offline())));
                }
            }
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write(json.writeValueAsString(Map.of("ok", false, "message", e.getMessage())));
        }
    }

    private String page() {
        return PAGE
                .replace("@@MODEL@@", escapeAttr(model))
                .replace("@@DOCS@@", String.valueOf(docCount()))
                .replace("@@TEMP@@", String.valueOf(temperature));
    }

    private String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CompanionAI</title>
<style>
:root {
  --bg: #eef1f6;
  --panel: #ffffff;
  --border: #dce1ea;
  --text: #1f2733;
  --muted: #6b7686;
  --primary: #3d5afe;
  --primary-dark: #303f9f;
  --user-bubble: #e8edff;
  --assistant-bubble: #ffffff;
}
* { box-sizing: border-box; }
html, body { height: 100%; }
body {
  margin: 0;
  font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  background: var(--bg);
  color: var(--text);
  display: flex;
  flex-direction: column;
}
header {
  background: linear-gradient(90deg, #1c2a52, #2c3e6b);
  color: #fff;
  padding: 14px 22px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  box-shadow: 0 2px 6px rgba(0,0,0,.15);
}
header h1 { margin: 0; font-size: 19px; font-weight: 600; letter-spacing: .3px; }
header .status { font-size: 12.5px; color: #c6d0ea; }
header .badge {
  background: rgba(255,255,255,.14);
  border: 1px solid rgba(255,255,255,.25);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12.5px;
}
main {
  flex: 1;
  overflow-y: auto;
  padding: 20px 0 16px;
}
.chat {
  max-width: 800px;
  margin: 0 auto;
  padding: 0 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.msg { display: flex; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 15px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-wrap: break-word;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
}
.msg.user .bubble { background: var(--user-bubble); border-top-right-radius: 4px; }
.msg.assistant .bubble { background: var(--assistant-bubble); border: 1px solid var(--border); border-top-left-radius: 4px; }
.msg .name { font-size: 11.5px; color: var(--muted); margin-bottom: 3px; }
.msg.assistant .offline-tag {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: #b26a00;
}
.empty-hint {
  text-align: center;
  color: var(--muted);
  margin-top: 8vh;
  font-size: 14.5px;
}
.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 14px;
  padding: 10px 14px;
}
.thinking .dots { display: flex; gap: 4px; }
.thinking .dots span {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--primary);
  animation: blink 1.2s infinite ease-in-out;
}
.thinking .dots span:nth-child(2) { animation-delay: .2s; }
.thinking .dots span:nth-child(3) { animation-delay: .4s; }
@keyframes blink { 0%,80%,100% { opacity: .25; transform: scale(.85);} 40% { opacity: 1; transform: scale(1);} }
.bubble > :first-child { margin-top: 0; }
.bubble > :last-child { margin-bottom: 0; }
.bubble h1, .bubble h2, .bubble h3, .bubble h4, .bubble h5, .bubble h6 {
  margin: 14px 0 6px;
  line-height: 1.3;
  color: var(--text);
}
.bubble h1 { font-size: 19px; } .bubble h2 { font-size: 17px; } .bubble h3 { font-size: 15.5px; }
.bubble h4, .bubble h5, .bubble h6 { font-size: 15px; }
.bubble p { margin: 6px 0; }
.bubble ul, .bubble ol { margin: 6px 0; padding-left: 22px; }
.bubble li { margin: 3px 0; }
.bubble code {
  background: rgba(60,70,120,.12);
  border-radius: 5px;
  padding: 1px 5px;
  font-size: 13px;
  font-family: ui-monospace, Consolas, "Courier New", monospace;
}
.bubble pre {
  background: #1e2433;
  color: #e6e9f2;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 13px;
  line-height: 1.5;
}
.bubble pre code { background: transparent; color: inherit; padding: 0; }
.bubble blockquote {
  border-left: 3px solid var(--primary);
  margin: 8px 0;
  padding: 2px 12px;
  color: var(--muted);
  background: rgba(61,90,254,.06);
  border-radius: 0 8px 8px 0;
}
.bubble hr { border: none; border-top: 1px solid var(--border); margin: 12px 0; }
.bubble table { border-collapse: collapse; margin: 8px 0; font-size: 13.5px; min-width: 60%; }
.bubble th, .bubble td { border: 1px solid var(--border); padding: 6px 10px; text-align: left; }
.bubble th { background: rgba(61,90,254,.08); font-weight: 600; }
.bubble a { color: var(--primary); word-break: break-all; }
footer {
  background: var(--panel);
  border-top: 1px solid var(--border);
  box-shadow: 0 -2px 8px rgba(0,0,0,.06);
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
}
.composer {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.input-row { display: flex; gap: 10px; align-items: flex-end; }
textarea {
  flex: 1;
  resize: vertical;
  min-height: 78px;
  max-height: 220px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font: inherit;
  font-size: 15px;
  line-height: 1.4;
  background: #fafbfe;
  color: var(--text);
  outline: none;
}
textarea:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(61,90,254,.12); background: #fff; }
.btn {
  border: none;
  border-radius: 10px;
  padding: 11px 20px;
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
  background: var(--primary);
  transition: background .15s ease, transform .05s ease;
}
.btn:hover { background: var(--primary-dark); }
.btn:active { transform: translateY(1px); }
.btn:disabled { background: #9aa8d8; cursor: not-allowed; }
.upload-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  border-top: 1px dashed var(--border);
  padding-top: 10px;
}
.upload-bar span { font-size: 13px; color: var(--muted); }
.file-label {
  font-size: 13.5px;
  color: var(--primary);
  cursor: pointer;
  background: var(--user-bubble);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 12px;
}
.file-label:hover { background: #dbe4ff; }
#file-input { display: none; }
#file-name { font-size: 12.5px; color: var(--muted); max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.toast {
  position: fixed;
  top: 70px;
  left: 50%;
  transform: translateX(-50%);
  background: #263238;
  color: #fff;
  padding: 10px 18px;
  border-radius: 10px;
  font-size: 13.5px;
  box-shadow: 0 4px 14px rgba(0,0,0,.25);
  opacity: 0;
  pointer-events: none;
  transition: opacity .25s ease;
  max-width: 90vw;
  z-index: 10;
}
.toast.show { opacity: 1; }
details.stats { position: relative; }
details.stats summary {
  cursor: pointer;
  background: rgba(255,255,255,.14);
  border: 1px solid rgba(255,255,255,.25);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12.5px;
  list-style: none;
  user-select: none;
}
details.stats summary::-webkit-details-marker { display: none; }
details.stats .stat-grid {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  background: #fff;
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 6px 20px rgba(0,0,0,.15);
  padding: 12px 14px;
  font-size: 12.5px;
  display: grid;
  grid-template-columns: auto auto;
  gap: 4px 18px;
  min-width: 260px;
  z-index: 20;
}
details.stats .stat-grid span { color: var(--muted); white-space: nowrap; }
details.stats .stat-grid b { color: var(--text); font-weight: 600; }
</style>
</head>
<body>
<header>
  <h1>CompanionAI</h1>
  <div class="status">
    <span class="badge">Model: @@MODEL@@</span>
    <span class="badge">Docs: <span id="doc-count">@@DOCS@@</span></span>
    <span class="badge">Temp: @@TEMP@@</span>
    <span class="badge">Up: <span id="stat-up">-</span></span>
  </div>
  <details class="stats">
    <summary>Usage stats</summary>
    <div class="stat-grid">
      <span>Questions:</span><b id="st-total">0</b>
      <span>Streamed replies:</span><b id="st-streamed">0</b>
      <span>Offline replies:</span><b id="st-offline">0</b>
      <span>Est. input tokens:</span><b id="st-in">0</b>
      <span>Est. output tokens:</span><b id="st-out">0</b>
      <span>URLs fetched:</span><b id="st-fetch">0</b>
      <span>Fetch failures:</span><b id="st-fetch-err">0</b>
      <span>Avg reply latency:</span><b id="st-lat">0 ms</b>
      <span>Last reply:</span><b id="st-last">-</b>
    </div>
  </details>
</header>

<main>
  <div class="chat" id="chat-log">
    <div class="empty-hint">Ask a question, paste a URL and I'll fetch and read it, or just say hello. Your conversation will stay here so you can scroll back through it.</div>
  </div>
</main>

<footer>
  <div class="composer">
    <div class="input-row">
      <textarea id="msg" rows="4" placeholder="Ask a question, or paste a URL and I'll read it. (Shift+Enter for a new line)"></textarea>
      <button class="btn" id="send-btn">Send</button>
    </div>
    <div class="upload-bar">
      <label class="file-label" for="file-input">Upload document (.txt, .docx, .pdf)</label>
      <input type="file" id="file-input" accept=".txt,.docx,.pdf">
      <span id="file-name"></span>
      <button class="btn" id="upload-btn" style="padding:6px 14px;font-size:13.5px;">Upload</button>
    </div>
  </div>
</footer>

<div class="toast" id="toast"></div>

<script>
const chatLog = document.getElementById('chat-log');
let emptyHint = chatLog.querySelector('.empty-hint');
const input = document.getElementById('msg');
const sendBtn = document.getElementById('send-btn');
const fileInput = document.getElementById('file-input');
const uploadBtn = document.getElementById('upload-btn');
const fileName = document.getElementById('file-name');
const docCount = document.getElementById('doc-count');

function tone(ctx, freq, start, dur, vol) {
  const o = ctx.createOscillator();
  const g = ctx.createGain();
  o.type = 'sine';
  o.frequency.value = freq;
  o.connect(g);
  g.connect(ctx.destination);
  g.gain.setValueAtTime(vol, start);
  g.gain.exponentialRampToValueAtTime(0.001, start + dur);
  o.start(start);
  o.stop(start + dur);
}
function beep(kind) {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (kind === 'send') {
      tone(ctx, 520, ctx.currentTime, 0.12, .18);
    } else if (kind === 'answer') {
      tone(ctx, 784, ctx.currentTime, 0.1, .18);
      tone(ctx, 1175, ctx.currentTime + 0.11, 0.16, .18);
    } else if (kind === 'upload') {
      tone(ctx, 660, ctx.currentTime, 0.1, .18);
      tone(ctx, 880, ctx.currentTime + 0.1, 0.12, .18);
    }
  } catch (e) { /* audio not available */ }
}
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), 3500);
}
function fmtUptime(s) {
  if (s >= 3600) return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';
  if (s >= 60) return Math.floor(s / 60) + 'm';
  return s + 's';
}
function fmtNum(n) {
  if (n >= 1000) return (n / 1000).toFixed(1).replace(/\\.0$/, '') + 'k';
  return String(n);
}
async function refreshStats() {
  try {
    const res = await fetch('/api/stats');
    const d = await res.json();
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
    set('stat-up', fmtUptime(d.uptimeS || 0));
    set('st-total', String(d.total || 0));
    set('st-streamed', String(d.streamed || 0));
    set('st-offline', String(d.offline || 0));
    set('st-in', fmtNum(d.inputTokens || 0));
    set('st-out', fmtNum(d.outputTokens || 0));
    set('st-fetch', String(d.urlFetches || 0));
    set('st-fetch-err', String(d.fetchErrors || 0));
    set('st-lat', (d.avgLatencyMs || 0) + ' ms');
    set('st-last', (d.lastLatencyMs ? d.lastLatencyMs + ' ms / ' + fmtNum(d.lastOutputTokens) + ' tok' : '-'));
  } catch (e) { /* stats unavailable */ }
}
refreshStats();
function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function mdInline(s) {
  s = s.replace(/`([^`\\n]+)`/g, '<code>$1</code>');
  s = s.replace(/\\*\\*([^*\\n]+)\\*\\*/g, '<strong>$1</strong>');
  s = s.replace(/\\*([^*\\n]+)\\*/g, '<em>$1</em>');
  s = s.replace(/\\[([^\\]]+)\\]\\(([^)\\s"']+)\\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  return s;
}
function mdRow(l) {
  return l.trim().replace(/^\\|/, '').replace(/\\|$/, '').split('|').map(function (c) { return c.trim(); });
}
function mdRender(src) {
  const lines = esc(String(src)).replace(/\\r\\n?/g, '\\n').split('\\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const t = lines[i].trim();
    const fence = t.match(/^```([\\w-]*)\\s*$/);
    if (fence) {
      i++;
      const buf = [];
      while (i < lines.length && !/^```/.test(lines[i].trim())) { buf.push(lines[i]); i++; }
      i++;
      out.push('<pre><code' + (fence[1] ? ' class="language-' + fence[1] + '"' : '') + '>' + buf.join('\\n') + '</code></pre>');
      continue;
    }
    const h = t.match(/^(#{1,6})\\s+(.+?)\\s*#*\\s*$/);
    if (h) {
      const n = h[1].length;
      out.push('<h' + n + '>' + mdInline(h[2]) + '</h' + n + '>');
      i++; continue;
    }
    if (/^([-*_])(\\s*\\1){2,}\\s*$/.test(t)) { out.push('<hr>'); i++; continue; }
    if (t.indexOf('&gt;') === 0) {
      const buf = [];
      while (i < lines.length && lines[i].trim().indexOf('&gt;') === 0) { buf.push(lines[i].trim().slice(4).trim()); i++; }
      out.push('<blockquote><p>' + buf.map(mdInline).join('<br>') + '</p></blockquote>');
      continue;
    }
    if (t.indexOf('|') !== -1 && i + 1 < lines.length) {
      const sep = lines[i + 1].trim();
      if (sep.indexOf('-') !== -1 && /^\\s*\\|?[\\s:\\-|]+\\|?\\s*$/.test(sep)) {
        const header = mdRow(lines[i]);
        const aligns = mdRow(sep).map(function (c) {
          if (c.charAt(0) === ':' && c.charAt(c.length - 1) === ':') return 'center';
          if (c.charAt(0) === ':') return 'left';
          if (c.charAt(c.length - 1) === ':') return 'right';
          return '';
        });
        i += 2;
        const rows = [];
        while (i < lines.length && lines[i].trim().indexOf('|') !== -1) { rows.push(mdRow(lines[i])); i++; }
        const th = header.map(function (c, k) {
          return '<th' + (aligns[k] ? ' style="text-align:' + aligns[k] + '"' : '') + '>' + mdInline(c) + '</th>';
        }).join('');
        const tr = rows.map(function (r) {
          return '<tr>' + r.map(function (c, k) {
            return '<td' + (aligns[k] ? ' style="text-align:' + aligns[k] + '"' : '') + '>' + mdInline(c) + '</td>';
          }).join('') + '</tr>';
        }).join('');
        out.push('<table><thead><tr>' + th + '</tr></thead><tbody>' + tr + '</tbody></table>');
        continue;
      }
    }
    const ulm = t.match(/^([-*+])\\s+(.*)$/);
    const olm = t.match(/^(\\d+[.)])\\s+(.*)$/);
    if (ulm || olm) {
      const tag = ulm ? 'ul' : 'ol';
      const items = [];
      while (i < lines.length) {
        const tt = lines[i].trim();
        let m2 = tt.match(/^([-*+])\\s+(.*)$/);
        if (!m2) m2 = tt.match(/^(\\d+[.)])\\s+(.*)$/);
        if (!m2) break;
        items.push('<li>' + mdInline(m2[2]) + '</li>');
        i++;
      }
      out.push('<' + tag + '>' + items.join('') + '</' + tag + '>');
      continue;
    }
    if (t === '') { i++; continue; }
    const buf = [t];
    i++;
    while (i < lines.length && lines[i].trim() !== '' &&
        !/^(#{1,6}\\s|```|&gt;|[-*+]\\s|\\d+[.)]\\s)/.test(lines[i].trim())) {
      buf.push(lines[i].trim());
      i++;
    }
    out.push('<p>' + buf.map(mdInline).join(' ') + '</p>');
  }
  return out.join('');
}
function addMsg(role, text, offline) {
  if (emptyHint) { emptyHint.remove(); emptyHint = null; }
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + role;
  const inner = document.createElement('div');
  inner.className = 'bubble';
  if (role === 'assistant') {
    inner.innerHTML = mdRender(text);
  } else {
    inner.textContent = text;
  }
  wrap.appendChild(inner);
  if (offline) {
    const tag = document.createElement('span');
    tag.className = 'offline-tag';
    tag.textContent = 'offline reply';
    inner.appendChild(tag);
  }
  chatLog.appendChild(wrap);
  chatLog.scrollTo({ top: chatLog.scrollHeight, behavior: 'smooth' });
}
function autoGrow() {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 220) + 'px';
}
input.addEventListener('input', autoGrow);

async function send() {
  const text = input.value.trim();
  if (!text || sendBtn.disabled) return;
  addMsg('user', text);
  let thinkEl = document.createElement('div');
  thinkEl.className = 'msg assistant thinking';
  thinkEl.innerHTML = '<span>thinking</span><span class="dots"><span></span><span></span><span></span></span>';
  chatLog.appendChild(thinkEl);
  chatLog.scrollTo({ top: chatLog.scrollHeight, behavior: 'smooth' });
  input.value = '';
  autoGrow();
  sendBtn.disabled = true;
  beep('send');
  let bubble = null;
  let inner = null;
  let acc = '';
  let offline = false;
  try {
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text })
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    if (!res.body) throw new Error('Streaming is not supported in this browser.');
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\\n\\n')) !== -1) {
        const raw = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const lines = raw.split('\\n');
        for (const line of lines) {
          if (line.indexOf('data:') !== 0) continue;
          const data = JSON.parse(line.slice(5).trim());
          if (data.error) throw new Error(data.error);
          if (data.status && thinkEl) thinkEl.firstElementChild.textContent = data.status;
          if (data.offline) offline = true;
          if (data.delta) {
            acc += data.delta;
            if (!bubble) {
              thinkEl.remove();
              thinkEl = null;
              bubble = document.createElement('div');
              bubble.className = 'msg assistant';
              inner = document.createElement('div');
              inner.className = 'bubble';
              bubble.appendChild(inner);
              chatLog.appendChild(bubble);
              chatLog.scrollTo({ top: chatLog.scrollHeight, behavior: 'smooth' });
            }
            inner.innerHTML = mdRender(acc);
            await new Promise(r => requestAnimationFrame(() => r()));
          }
        }
      }
    }
    buf += decoder.decode();
    if (bubble) {
      if (offline) {
        const tag = document.createElement('span');
        tag.className = 'offline-tag';
        tag.textContent = 'offline reply';
        inner.appendChild(tag);
      }
      beep('answer');
      chatLog.scrollTo({ top: chatLog.scrollHeight, behavior: 'smooth' });
    } else {
      if (thinkEl) thinkEl.remove();
      addMsg('assistant', '(The model returned an empty reply.)');
    }
  } catch (e) {
    if (thinkEl) thinkEl.remove();
    if (bubble) bubble.remove();
    addMsg('assistant', 'Sorry, something went wrong: ' + e.message);
    beep('answer');
  } finally {
    sendBtn.disabled = false;
    input.focus();
    refreshStats();
  }
}
sendBtn.addEventListener('click', send);
input.addEventListener('keydown', function (e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
});

fileInput.addEventListener('change', function () {
  fileName.textContent = fileInput.files[0] ? fileInput.files[0].name : '';
});
uploadBtn.addEventListener('click', async function () {
  if (!fileInput.files.length) { toast('Choose a file first.'); return; }
  const fd = new FormData();
  fd.append('doc', fileInput.files[0]);
  uploadBtn.disabled = true;
  try {
    const res = await fetch('/upload', { method: 'POST', body: fd });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
    docCount.textContent = data.docs;
    toast(data.message || 'Upload complete.');
    beep('upload');
    refreshStats();
  } catch (e) {
    toast('Upload failed: ' + e.message);
    beep('answer');
  } finally {
    uploadBtn.disabled = false;
    fileInput.value = '';
    fileName.textContent = '';
  }
});
</script>
</body>
</html>
""";
}