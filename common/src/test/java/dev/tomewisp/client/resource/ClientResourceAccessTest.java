package dev.tomewisp.client.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientResourceAccessTest {
    @Test
    void exposesCompletePackStackAndSelectedHighestPriority() {
        ClientResourceAccess access = prefix -> List.of(
                new ClientResource("example:books/guide.json", "base", 0, false, "old"),
                new ClientResource("example:books/guide.json", "override", 1, true, "new"));

        assertEquals(2, access.list("books").size());
        assertEquals("new", access.selected("books").getFirst().content());
        assertEquals("override", access.selected("books").getFirst().packId());
    }

    @Test
    void rejectsTraversalAbsoluteAndNamespacedPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> ClientResourceAccess.validatePrefix("../secrets"));
        assertThrows(IllegalArgumentException.class, () -> ClientResourceAccess.validatePrefix("/assets"));
        assertThrows(IllegalArgumentException.class, () -> ClientResourceAccess.validatePrefix("mod:books"));
    }
}
