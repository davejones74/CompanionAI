package davejones74.campanionai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import davejones74.campanionai.FileRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path dataDir;

    private ObjectNode args(String filename, String mime, String content) {
        ObjectNode n = M.createObjectNode();
        n.put("filename", filename);
        n.put("mimeType", mime);
        n.put("content", content);
        return n;
    }

    @Test
    void createsMarkdown() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        FileRef f = te.createFile(args("notes.md", "text/markdown", "# Hi"));
        assertEquals("notes.md", f.name());
        assertTrue(f.url().startsWith("/api/files/"));
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("notes.md")));
    }

    @Test
    void createsTxtJsonCsv() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        te.createFile(args("a.txt", "text/plain", "hello"));
        te.createFile(args("b.json", "application/json", "{}"));
        te.createFile(args("c.csv", "text/csv", "x,y"));
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("a.txt")));
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("b.json")));
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("c.csv")));
    }

    @Test
    void sanitisesPathTraversal() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        FileRef f = te.createFile(args("../../etc/evil.md", "text/markdown", "x"));
        assertEquals("evil.md", f.name());
        assertTrue(Files.exists(dataDir.resolve("generated").resolve("evil.md")));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        assertThrows(IOException.class, () -> te.createFile(args("evil.exe", "application/x-msdownload", "x")));
        assertThrows(IOException.class, () -> te.createFile(args("noext", "text/plain", "x")));
    }

    @Test
    void rejectsOversize() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        String big = "x".repeat((int) (ToolExecutor.MAX_CONTENT_BYTES + 1));
        assertThrows(IOException.class, () -> te.createFile(args("big.md", "text/markdown", big)));
    }

    @Test
    void handlesToolCallEnvelope() throws Exception {
        ToolExecutor te = new ToolExecutor(dataDir);
        ObjectNode call = M.createObjectNode();
        ObjectNode fn = M.createObjectNode();
        fn.put("name", "create_file");
        fn.set("arguments", args("t.md", "text/markdown", "# T"));
        call.set("function", fn);
        ToolResult tr = te.execute(call);
        assertEquals(1, tr.files().size());
        assertEquals("t.md", tr.files().get(0).name());
    }
}
