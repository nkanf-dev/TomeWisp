package dev.tomewisp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ToolResultTest {
    @Test
    void enforcesValues() {
        assertEquals("ok", new ToolResult.Success<>("ok").value());
        assertThrows(NullPointerException.class, () -> new ToolResult.Success<>(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult.Failure<String>("", "message"));
    }
}
