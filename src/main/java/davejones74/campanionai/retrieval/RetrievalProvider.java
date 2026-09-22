package davejones74.campanionai.retrieval;

public interface RetrievalProvider {

    RetrievalKind kind();

    boolean isConfigured();

    RetrievalResult retrieve(RetrievalRequest request) throws RetrievalException;
}