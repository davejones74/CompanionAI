package davejones74.campanionai;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@MultipartConfig(maxFileSize = 10 * 1024 * 1024, maxRequestSize = 12 * 1024 * 1024)
public class ModelServlet extends HttpServlet {
    private Vocabulary vocab;
    private MlpLanguageModel model;
    private Path dataDir;
    private final ChatRules chat = new ChatRules();

    private final int hiddenSize = Integer.getInteger("campanionai.hiddenSize", 512);
    private final int contextWindow = Integer.getInteger("campanionai.contextWindow", 5);
    private final double temperature = System.getProperty("campanionai.temperature") == null
            ? 0.8
            : Double.parseDouble(System.getProperty("campanionai.temperature"));

    @Override
    public void init() {
        try {
            String base = System.getProperty("user.dir");
            dataDir = Path.of(base, "data").toAbsolutePath();
            Files.createDirectories(dataDir);

            List<String> corpus = new ArrayList<>(readStoredDocuments());

            rebuildModel(corpus);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise model", e);
        }
    }

    private List<String> readStoredDocuments() throws IOException {
        List<String> docs = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    docs.add(DocumentReader.extract(p.getFileName().toString(), in));
                }
            }
        }
        return docs;
    }

    private void rebuildModel(List<String> docs) {
        vocab = new Vocabulary();
        StringBuilder all = new StringBuilder();
        for (String doc : docs) {
            all.append(doc).append('\n');
        }
        String text = all.toString();
        vocab.build(text);
        model = new MlpLanguageModel(vocab.size(), hiddenSize, contextWindow);
        train(text);
    }

    private void train(String text) {
        String[] tokens = text.split("\\s+");
        int[] idxs = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            idxs[i] = vocab.getIdx(tokens[i]);
        }
        for (int i = 0; i < idxs.length - 1; i++) {
            model.train(makeContext(idxs, i), idxs[i + 1]);
        }
    }

    private int[] makeContext(int[] idxs, int endIdx) {
        int[] ctx = new int[contextWindow];
        int fill = 0;
        for (int p = Math.max(0, endIdx - contextWindow + 1); p <= endIdx; p++) {
            ctx[fill++] = idxs[p];
        }
        return ctx;
    }

    private String respond(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "Please type something first.";
        }
        String greeting = chat.reply(input);
        if (greeting != null) {
            return greeting;
        }
        String[] tokens = input.toLowerCase().split("\\s+");
        int[] idxs = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            idxs[i] = vocab.getIdx(tokens[i]);
        }
        int[] context = makeContext(idxs, idxs.length - 1);
        StringBuilder reply = new StringBuilder();
        int generated = 0;
        String lastWord = "";
        int repeats = 0;
        while (generated < 8) {
            int next = model.sampleIndex(context, temperature);
            if (next == 0) {
                break;
            }
            String word = vocab.getWord(next);
            if (word.equals(lastWord)) {
                repeats++;
                if (repeats > 2) {
                    break;
                }
            } else {
                repeats = 0;
            }
            lastWord = word;
            reply.append(word).append(' ');
            generated++;
            if (word.endsWith(".")) {
                break;
            }
            System.arraycopy(context, 1, context, 0, context.length - 1);
            context[context.length - 1] = next;
        }
        String out = reply.toString().trim();
        return out.isEmpty() ? "I don't have a good answer for that yet." : out;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html; charset=UTF-8");
        resp.getWriter().write(page(null, null));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        String reply = null;
        String status = null;
        try {
            String text = req.getParameter("text");
            boolean isMultipart = req.getContentType() != null
                    && req.getContentType().toLowerCase().startsWith("multipart/");
            if (isMultipart && req.getPart("doc") != null && req.getPart("doc").getSize() > 0) {
                Part doc = req.getPart("doc");
                String filename = Path.of(doc.getSubmittedFileName()).getFileName().toString();
                Path saved = saveUpload(filename, doc);
                List<String> corpus = new ArrayList<>();
                corpus.add(vocabText());
                try (InputStream in = Files.newInputStream(saved)) {
                    corpus.add(DocumentReader.extract(filename, in));
                }
                rebuildModel(corpus);
                status = "Uploaded '" + filename + "' and re-trained. Vocabulary now " + vocab.size() + " tokens.";
            } else if (text != null && !text.isBlank()) {
                reply = respond(text);
            }
        } catch (Exception e) {
            status = "Error: " + e.getMessage();
        }
        resp.setContentType("text/html; charset=UTF-8");
        resp.getWriter().write(page(reply, status));
    }

    private String vocabText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vocab.size(); i++) {
            sb.append(vocab.getWord(i)).append(' ');
        }
        return sb.toString();
    }

    private Path saveUpload(String filename, Part part) throws IOException {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = dataDir.resolve(safe);
        if (Files.exists(target)) {
            target = dataDir.resolve(System.currentTimeMillis() + "_" + safe);
        }
        try (InputStream in = part.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private String page(String reply, String status) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>CompanionAI</title></head>")
            .append("<body style='font-family:sans-serif;margin:2rem;'>")
            .append("<h1>CompanionAI</h1>");

        html.append("<h2>Interact</h2>")
            .append("<form method='post' action='/'>")
            .append("<label for='text'>Your message:</label><br>")
            .append("<input type='text' id='text' name='text' size='60' autofocus><br><br>")
            .append("<button type='submit'>Send</button>")
            .append("</form><br>");

        if (reply != null) {
            html.append("<h3>Model reply</h3><p>").append(escape(reply)).append("</p>");
        }

        html.append("<h2>Train</h2>")
            .append("<form method='post' action='/' enctype='multipart/form-data'>")
            .append("<label for='doc'>Upload a document (.txt, .docx, .pdf):</label><br>")
            .append("<input type='file' id='doc' name='doc' accept='.txt,.docx,.pdf'><br><br>")
            .append("<button type='submit'>Upload &amp; Train</button>")
            .append("</form>");

        if (status != null) {
            html.append("<p><strong>").append(escape(status)).append("</strong></p>");
        }

        html.append("<p>Vocabulary size: ").append(vocab != null ? vocab.size() : 0)
            .append(" | Hidden: ").append(hiddenSize)
            .append(" | Context: ").append(contextWindow)
            .append(" | Temperature: ").append(temperature)
            .append("</p>")
            .append("</body></html>");
        return html.toString();
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}