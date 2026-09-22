package davejones74.campanionai.retrieval;

public record RetrievalItem(
        String source,
        String title,
        String url,
        String published,
        String snippet,
        String content) {

    public RetrievalItem {
        source = source == null ? "" : source;
        title = title == null ? "" : title;
        url = url == null ? null : url;
        published = published == null ? null : published;
        snippet = snippet == null ? null : snippet;
        content = content == null ? null : content;
    }
}