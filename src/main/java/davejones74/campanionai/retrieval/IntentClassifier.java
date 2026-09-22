package davejones74.campanionai.retrieval;

import davejones74.campanionai.LlmClient;

import java.util.List;
import java.util.Optional;

public interface IntentClassifier {

    Optional<IntentClassification> classify(String input, List<LlmClient.ChatMessage> history);
}