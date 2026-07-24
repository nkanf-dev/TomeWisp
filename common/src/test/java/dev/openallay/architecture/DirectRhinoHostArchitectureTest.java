package dev.openallay.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DirectRhinoHostArchitectureTest {
    @Test
    void runtimeNeverSerializesRequestOrWorkspaceInputIntoSource() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/dev/openallay/script/RhinoJavascriptRuntime.java"));

        assertFalse(runtime.contains("import com.google.gson.Gson"));
        assertFalse(runtime.contains("minecraftData.toString()"));
        assertFalse(runtime.contains("workspaceValues.toString()"));
        assertFalse(runtime.contains("JSON.parse(%s)"));
        assertFalse(runtime.contains("gson.toJson"));
        assertTrue(runtime.contains("new RhinoHostAdapter(context, scope)"));
        assertTrue(runtime.contains("adapter.adapt(minecraftRoots)"));
        assertTrue(runtime.contains("adapter.adapt(value)"));
    }

    @Test
    void hostAdapterNeverUsesRhinosGenericJavaWrappers() throws IOException {
        String adapter = Files.readString(Path.of(
                "src/main/java/dev/openallay/script/host/RhinoHostAdapter.java"));
        String object = Files.readString(Path.of(
                "src/main/java/dev/openallay/script/host/HostObjectView.java"));
        String list = Files.readString(Path.of(
                "src/main/java/dev/openallay/script/host/HostListView.java"));
        String sources = adapter + object + list;

        assertFalse(sources.contains("NativeJavaObject"));
        assertFalse(sources.contains("NativeJavaList"));
        assertFalse(sources.contains("NativeJavaMap"));
        assertFalse(sources.contains("implements Wrapper"));
        assertFalse(sources.contains("javaToJS"));
        assertTrue(object.contains("value.getClass().isRecord()")
                || adapter.contains("value.getClass().isRecord()"));
    }
}
