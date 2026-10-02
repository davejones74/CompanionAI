package davejones74.campanionai.retrieval;

import davejones74.campanionai.llm.LlmMessage;

import java.util.List;
import java.util.Optional;

public interface IntentClassifier {

    Optional<IntentClassification> classify(String input, List<LlmMessage> history);
}