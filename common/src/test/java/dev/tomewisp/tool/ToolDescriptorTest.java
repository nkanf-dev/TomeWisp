package dev.tomewisp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ToolDescriptorTest {
    record Input(String value) {}

    record Output(String value) {}

    @Test
    void acceptsNamespacedId() {
        var descriptor = new ToolDescriptor<>(
                "tomewisp:echo", "Echo", Input.class, Output.class, ToolAccess.READ_ONLY);
        assertEquals("tomewisp:echo", descriptor.id());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolDescriptor<>(
                        "Echo Tool", "Echo", Input.class, Output.class, ToolAccess.READ_ONLY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolDescriptor<>(
                        "tomewisp:echo", " ", Input.class, Output.class, ToolAccess.READ_ONLY));
    }
}
