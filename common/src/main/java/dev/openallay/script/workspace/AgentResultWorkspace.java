package dev.openallay.script.workspace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentResultWorkspace implements AutoCloseable {
    private final String prefix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, JsonElement> values = new LinkedHashMap<>();
    private boolean closed;

    public synchronized String store(JsonElement value) {
        requireOpen();
        String handle = "r_" + prefix + "_" + sequence.incrementAndGet();
        values.put(handle, value.deepCopy());
        return handle;
    }

    public synchronized JsonElement open(String handle) {
        requireOpen();
        JsonElement value = values.get(handle);
        if (value == null) {
            throw new WorkspaceException(
                    "workspace_handle_unavailable",
                    "Result handle is unavailable in this request");
        }
        return value.deepCopy();
    }

    public synchronized JsonObject select(Collection<String> handles) {
        requireOpen();
        JsonObject selected = new JsonObject();
        for (String handle : handles == null ? java.util.List.<String>of() : handles) {
            selected.add(handle, open(handle));
        }
        return selected;
    }

    public synchronized int size() {
        return values.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        values.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new WorkspaceException(
                    "workspace_closed", "Result workspace is already closed");
        }
    }
}

