package davejones74.campanionai;

public final class Tokens {

    private Tokens() {
    }

    public static int estimate(String s) {
        return (s.length() + 3) / 4;
    }
}