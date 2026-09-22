package davejones74.campanionai.retrieval;

public record IntentClassification(
        Intent intent,
        String location,
        String team,
        int pastDays) {

    public IntentClassification {
        if (intent == null) intent = Intent.NONE;
        location = location == null ? null : location.trim();
        if (location != null && location.isEmpty()) location = null;
        team = team == null ? null : team.trim();
        if (team != null && team.isEmpty()) team = null;
        if (pastDays < 0) pastDays = 0;
    }

    public IntentClassification(Intent intent) {
        this(intent, null, null, 0);
    }
}