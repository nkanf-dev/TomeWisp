package dev.openallay.script.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AgentResultWorkspaceTest {
    @Test
    void storesReopensAndInvalidatesCanonicalResults() {
        AgentResultWorkspace workspace = new AgentResultWorkspace();
        String handle = workspace.store(JsonParser.parseString("[1,2,3]"));

        assertEquals(3, workspace.open(handle).getAsJsonArray().size());
        assertEquals(1, workspace.select(List.of(handle)).size());

        workspace.close();
        WorkspaceException closed =
                assertThrows(WorkspaceException.class, () -> workspace.open(handle));
        assertEquals("workspace_closed", closed.code());
    }

    @Test
    void presentsLargeArraysWithoutCopyingEveryRowToModelText() {
        var value = JsonParser.parseString("""
                [
                  {"id":"a","damage":1},{"id":"b","damage":2},{"id":"c","damage":3},
                  {"id":"d","damage":4},{"id":"e","damage":5},{"id":"f","damage":6},
                  {"id":"g","damage":7},{"id":"h","damage":8}
                ]
                """);

        var result = new JavascriptResultPresenter().present("r_test", value);

        assertEquals(8, result.cardinality());
        assertEquals(2, result.omittedRows());
        assertTrue(result.modelText().contains("workspace.open(\"r_test\")"));
        assertTrue(result.modelText().length() < value.toString().length() + 200);
    }
}

