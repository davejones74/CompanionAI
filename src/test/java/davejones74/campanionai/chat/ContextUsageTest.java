package davejones74.campanionai.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextUsageTest {

    @Test
    void zeroUsage() {
        ContextUsage cu = ContextUsage.of(0, 8000);
        assertEquals(0, cu.percentage());
    }

    @Test
    void normalUsage() {
        ContextUsage cu = ContextUsage.of(3072, 8000);
        assertEquals(38, cu.percentage());
    }

    @Test
    void clampsOverLimit() {
        ContextUsage cu = ContextUsage.of(12000, 8000);
        assertEquals(100, cu.percentage());
    }

    @Test
    void clampsNegative() {
        ContextUsage cu = ContextUsage.of(-5, 8000);
        assertEquals(0, cu.percentage());
    }

    @Test
    void fullUsage() {
        assertEquals(100, ContextUsage.of(8000, 8000).percentage());
    }
}
