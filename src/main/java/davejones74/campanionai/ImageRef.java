package davejones74.campanionai;

/**
 * Reference to a user-uploaded image. The message persists this reference
 * only — never the image bytes, and never base64.
 */
public record ImageRef(String id, String filename, String mimeType, String url) {

    public ImageRef {
        id = id == null ? "" : id;
        filename = filename == null ? "" : filename;
        mimeType = mimeType == null ? "" : mimeType;
        url = url == null ? "" : url;
    }
}
