package davejones74.campanionai;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Override
    public void init() {
        try {
            String base = System.getProperty("user.dir");
            dataDir = Path.of(base, "data").toAbsolutePath();
            Files.createDirectories(dataDir);

            String greetings = loadResource("/greetings.txt");
            List<String> corpus = new ArrayList<>();
            corpus.add(greetings);
            corpus.addAll(readStoredDocuments());

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
        model = new MlpLanguageModel(vocab.size(), 64);
        train(text);
    }

    private void train(String text) {
        String[] tokens = text.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            model.train(vocab.getIdx(tokens[i]), vocab.getIdx(tokens[i + 1]));
        }
    }

    private String respond(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "Please type something first.";
        }
        String[] tokens = input.toLowerCase().split("\\s+");
        int current = vocab.getIdx(tokens[tokens.length - 1]);
        StringBuilder reply = new StringBuilder();
        int generated = 0;
        while (generated < 6) {
            MlpForwardResult result = model.forward(current);
            int next = argmax(result.probabilities);
            if (next == current) {
                break;
            }
            String word = vocab.getWord(next);
            reply.append(word).append(' ');
            generated++;
            if (word.endsWith(".")) {
                break;
            }
            current = next;
        }
        String out = reply.toString().trim();
        return out.isEmpty() ? "I don't have a good answer for that yet." : out;
    }

    private int argmax(double[] probs) {
        int best = 0;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > probs[best]) {
                best = i;
            }
        }
        return best;
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

        html.append("<p>Current vocabulary size: ").append(vocab != null ? vocab.size() : 0).append("</p>")
            .append("</body></html>");
        return html.toString();
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String loadResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}