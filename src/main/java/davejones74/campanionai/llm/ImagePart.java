package davejones74.campanionai.llm;

import java.util.Base64;

/**
 * One image attached to an {@link LlmMessage}. Provider-neutral: the provider
 * implementation decides how to encode it for its own API (an OpenAI-compatible
 * {@code image_url} data URL today, a native image field for future runtimes).
 */
public record ImagePart(String mimeType, byte[] data) {

    public ImagePart {
        if (data == null) {
            data = new byte[0];
        }
    }

    public String dataUrl() {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
    }
}
