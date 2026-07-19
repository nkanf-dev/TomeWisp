package dev.openallay.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ToolDescriptorTest {
    record Input(String value) {}

    record Output(String value) {}

    @Test
    void acceptsNamespacedId() {
        var descriptor = new ToolDescriptor<>(
                "openallay:echo", "Echo", Input.class, Output.class, ToolAccess.READ_ONLY);
        assertEquals("openallay:echo", descriptor.id());
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
                        "openallay:echo", " ", Input.class, Output.class, ToolAccess.READ_ONLY));
    }
}
