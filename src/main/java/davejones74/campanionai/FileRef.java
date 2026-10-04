package davejones74.campanionai;

public record FileRef(String name, String url, String mimeType, long size) {

    public FileRef {
        name = name == null ? "" : name;
        url = url == null ? "" : url;
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        if (size < 0) {
            size = 0;
        }
    }
}
