package dev.openallay.script.host;

import dev.openallay.script.JavascriptExecutionException;

/** Stable failure raised by the closed, read-only Rhino host-object surface. */
public final class HostAccessException extends JavascriptExecutionException {
    public HostAccessException(String code, String message) {
        super(code, message);
    }

    static HostAccessException readOnly() {
        return new HostAccessException(
                "javascript_host_read_only",
                "Minecraft host snapshots are read-only");
    }

    static HostAccessException unsupported(Class<?> type) {
        return new HostAccessException(
                "javascript_host_type_unsupported",
                "Unsupported detached Java host value: " + type.getName());
    }

    static HostAccessException unsupportedMapKey(Object key) {
        return new HostAccessException(
                "javascript_host_map_key_unsupported",
                "JavaScript host maps require String keys, found "
                        + (key == null ? "null" : key.getClass().getName()));
    }
}
