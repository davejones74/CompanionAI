package davejones74.campanionai.llm;

import davejones74.campanionai.Config;
import davejones74.campanionai.retrieval.WebSearchProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a failed local request may be retried against a hosted model, and builds the
 * request it is allowed to send.
 *
 * <p>The local runtime is the product. This class exists so that the moment the application stops
 * being local-first, that moment is a single, narrow, testable decision rather than a branch in
 * the request handler. Four rules, and all four are refusals by default:
 *
 * <ol>
 *   <li><b>Off unless configured.</b> {@code companionai.cloud.enabled} must be {@code true} and
 *       an API key must be present. A deployment that has never heard of a cloud model cannot
 *       send one, so an upgrade can never start disclosing data on its own.</li>
 *   <li><b>Only after a local failure.</b> Callers reach this class from the local
 *       {@link LlmException} handler and nowhere else. A failed Tavily search, a failed page
 *       fetch or an empty knowledge base leaves the local model in charge, because none of those
 *       is a reason to hand a third party the question.</li>
 *   <li><b>Research requests stay local.</b> A research request carries up to six fetched page
 *       bodies into its context. That is the largest payload this application can produce, and
 *       it is not a payload to send somewhere else without a deliberate decision. When the local
 *       model is down, such a request gets the offline reply instead.</li>
 *   <li><b>No history and no documents by default.</b> The outbound request carries the system
 *       instructions and the current question, and nothing else: not earlier turns of the
 *       conversation, and not knowledge-base documents. Those documents are the user's own files,
 *       and the local system prompt carries them, so they are stripped on the way out.
 *       {@code companionai.cloud.includeHistory} opts history back in for an operator who has
 *       decided that is acceptable; it does not restore the documents.</li>
 * </ol>
 *
 * <p>Instances are immutable and safe to share.
 */
public final class CloudFallback {

    private static final Logger LOG = LogManager.getLogger(CloudFallback.class);

    private final boolean enabled;
    private final LlmProvider provider;
    private final boolean includeHistory;
    private final String model;

    private CloudFallback(boolean enabled, LlmProvider provider, boolean includeHistory, String model) {
        this.enabled = enabled;
        this.provider = provider;
        this.includeHistory = includeHistory;
        this.model = model == null ? "" : model;
    }

    /** The disabled state. Nothing leaves the machine, whatever the configuration says. */
    public static CloudFallback disabled() {
        return new CloudFallback(false, null, false, "");
    }

    /**
     * Builds the fallback from configuration.
     *
     * <p>Requiring both the switch and a key means a half-finished configuration fails towards
     * staying local. A missing model name is the operator's to fill in; there is no default,
     * because guessing a hosted model name produces a 404 from a third party instead of a clear
     * message at startup.
     */
    public static CloudFallback fromConfig() {
        boolean wanted = Config.bool("companionai.cloud.enabled", "COMPANIONAI_CLOUD_ENABLED", false);
        String apiKey = Config.string("companionai.cloud.apiKey", "COMPANIONAI_CLOUD_API_KEY", "");
        String model = Config.string("companionai.cloud.model", "COMPANIONAI_CLOUD_MODEL", "");
        String baseUrl = Config.string("companionai.cloud.baseUrl", "COMPANIONAI_CLOUD_BASE_URL",
                OpenAiProvider.DEFAULT_BASE_URL);
        double temperature = Config.decimal("companionai.cloud.temperature", "COMPANIONAI_CLOUD_TEMPERATURE", 0.2);
        boolean history = Config.bool("companionai.cloud.includeHistory", "COMPANIONAI_CLOUD_INCLUDE_HISTORY", false);
        // The same output cap as the local provider: a runaway reply costs real money here.
        int maxTokens = Config.integer("companionai.llm.maxTokens", "LLM_MAX_TOKENS",
                LlmProviderFactory.DEFAULT_MAX_TOKENS);
        if (maxTokens <= 0) {
            maxTokens = LlmProviderFactory.DEFAULT_MAX_TOKENS;
        }

        if (!wanted) {
            return disabled();
        }
        if (apiKey.isBlank() || model.isBlank()) {
            LOG.warn("{} is true but {} is not set; the cloud fallback stays disabled.",
                    "companionai.cloud.enabled",
                    apiKey.isBlank() ? "COMPANIONAI_CLOUD_API_KEY" : "COMPANIONAI_CLOUD_MODEL");
            return disabled();
        }
        return new CloudFallback(true,
                new OpenAiProvider(model, baseUrl, apiKey, temperature, maxTokens), history, model);
    }

    /** Builds a fallback around an already-constructed provider. Used by tests. */
    public static CloudFallback of(LlmProvider provider, boolean includeHistory) {
        return provider == null
                ? disabled()
                : new CloudFallback(true, provider, includeHistory, provider.model());
    }

    public boolean enabled() {
        return enabled;
    }

    /** The hosted model that would answer, for display and logging. Empty when disabled. */
    public String model() {
        return model;
    }

    public boolean includesHistory() {
        return includeHistory;
    }

    /**
     * Whether a request that failed locally may be retried in the cloud.
     *
     * @param profile the retrieval profile the request ran under, or {@code null} when it was not
     *                a web request. Research is refused; see the class comment.
     */
    public boolean maySend(WebSearchProfile profile) {
        return enabled && provider != null && profile != WebSearchProfile.RESEARCH;
    }

    /** Why the request stayed local, for the log line. Never {@code null}. */
    public String refusalReason(WebSearchProfile profile) {
        if (!enabled || provider == null) {
            return "cloud fallback disabled";
        }
        if (profile == WebSearchProfile.RESEARCH) {
            return "research requests stay local";
        }
        return "permitted";
    }

    /** The hosted provider, or {@code null} when the fallback is disabled. */
    public LlmProvider provider() {
        return provider;
    }

    /**
     * The message list actually sent.
     *
     * <p>The system prompt is replaced rather than forwarded. The local one has the knowledge-base
     * documents appended to it, and those are the user's own files: sending them to a third party
     * because the local model happened to be down would be a disclosure nobody asked for. The
     * caller passes the reduced prompt built without them.
     *
     * <p>With history excluded this leaves the system prompt and the current question, which are
     * the first and last elements. Anything in between is earlier conversation.
     */
    public List<LlmMessage> outbound(List<LlmMessage> messages, String cloudSystemPrompt) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<LlmMessage> out = new ArrayList<>(messages.size());
        out.add(new LlmMessage("system", cloudSystemPrompt == null ? messages.get(0).content() : cloudSystemPrompt));
        if (includeHistory) {
            out.addAll(messages.subList(1, messages.size()));
        } else if (messages.size() > 1) {
            out.add(messages.get(messages.size() - 1));
        }
        return List.copyOf(out);
    }

    /** The text shown to the user when the cloud answered. */
    public String notice() {
        return "Answered by " + model + " because the local model was unavailable. "
                + "Your question and any live search results were sent to that provider."
                + (includeHistory ? "" : " Earlier turns of this conversation were not sent.");
    }
}