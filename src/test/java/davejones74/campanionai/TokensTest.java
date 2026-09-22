package davejones74.campanionai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokensTest {

    @Test
    void estimatesRoughlyOneTokenPerFourChars() {
        assertEquals(0, Tokens.estimate(""));
        assertEquals(1, Tokens.estimate("abc"));
        assertEquals(2, Tokens.estimate("hello"));
        assertEquals(25, Tokens.estimate("a".repeat(97)));
    }
}