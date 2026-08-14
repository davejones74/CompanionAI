package davejones74.campanionai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class DocumentReader {

    private DocumentReader() {
    }

    public static String extract(String filename, InputStream in) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".docx")) {
            return extractDocx(in);
        }
        if (lower.endsWith(".pdf")) {
            return extractPdf(in);
        }
        throw new IllegalArgumentException("Unsupported file type: " + filename
                + " (supported: .txt, .docx, .pdf)");
    }

    private static String extractDocx(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (!p.getText().isBlank()) {
                    sb.append(p.getText()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    private static String extractPdf(InputStream in) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}