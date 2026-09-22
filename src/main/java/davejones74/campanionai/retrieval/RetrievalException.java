package davejones74.campanionai.retrieval;

public class RetrievalException extends Exception {

    private final String userFacingMessage;

    public RetrievalException(String message) {
        this(message, message);
    }

    public RetrievalException(String message, Throwable cause) {
        this(message, message, cause);
    }

    public RetrievalException(String message, String userFacingMessage) {
        this(message, userFacingMessage, null);
    }

    public RetrievalException(String message, String userFacingMessage, Throwable cause) {
        super(message, cause);
        this.userFacingMessage = userFacingMessage == null || userFacingMessage.isBlank()
                ? "Current external information could not be retrieved."
                : userFacingMessage;
    }

    public String userFacingMessage() {
        return userFacingMessage;
    }
}