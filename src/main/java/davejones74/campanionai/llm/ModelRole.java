package davejones74.campanionai.llm;

/**
 * The two model roles CompanionAI resolves through configuration. Role is
 * separate from provider and from model name: a role says <em>what the model
 * is for</em>, never which runtime serves it.
 */
public enum ModelRole {
    VISION,
    MAIN
}
