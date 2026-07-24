package dev.openallay.script.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertSame(
                workspace.select(List.of(handle)).get(handle),
                workspace.select(List.of(handle)).get(handle));

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
        assertEquals(6, result.preview().getAsJsonArray().size());
        assertTrue(result.modelText().contains("workspace.open(\"r_test\")"));
        assertTrue(result.modelText().contains("scope: preview"));
        assertTrue(result.modelText().contains("id: a"));
        assertTrue(!result.modelText().contains("{\"id\""));
        assertTrue(result.modelText().length() < value.toString().length() + 200);
    }

    @Test
    void boundsStructuredPreviewBeforeRenderingLargeObjects() {
        com.google.gson.JsonObject value = new com.google.gson.JsonObject();
        for (int index = 0; index < 100; index++) {
            value.addProperty("field" + index, "界".repeat(1_000));
        }

        var result = new JavascriptResultPresenter().present("r_large", value);

        assertTrue(!result.complete());
        assertTrue(result.preview().getAsJsonObject().size() <= 16);
        assertTrue(result.omittedFields() >= 84);
        result.preview().getAsJsonObject().entrySet().forEach(entry ->
                assertTrue(entry.getValue().getAsString().length() <= 241));
        assertTrue(result.modelText().length() < 1_900);
    }

    @Test
    void rejectsTooManySelectedHandlesWithoutPartiallyOpeningThem() {
        AgentResultWorkspace workspace = new AgentResultWorkspace();
        java.util.ArrayList<String> handles = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            handles.add(workspace.store(JsonParser.parseString("[" + index + "]")));
        }

        WorkspaceException failure = assertThrows(
                WorkspaceException.class, () -> workspace.select(handles));

        assertEquals("workspace_selection_too_large", failure.code());
        assertEquals(5, workspace.size());
    }

    @Test
    void marksAnswerSizedResultsCompleteAndTellsModelToStopVerifying() {
        var result = new JavascriptResultPresenter().present(
                "r_complete", JsonParser.parseString("[{\"id\":\"example:sword\",\"damage\":14}]"));

        assertTrue(result.complete());
        assertTrue(result.modelText().contains("scope: complete"));
        assertTrue(result.modelText().contains(
                "do not call run_javascript again only to verify it"));
    }
}
