package davejones74.campanionai;

public record Source(String title, String url) {

    public Source {
        title = title == null ? "" : title;
        url = url == null ? null : url;
    }
}
