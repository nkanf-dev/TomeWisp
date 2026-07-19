package dev.tomewisp.bridge.client;

import dev.tomewisp.bridge.protocol.CapabilityPayload;
import java.util.List;

public final class RemoteCapabilityStore {
    private volatile CapabilityPayload snapshot = empty();

    public void replace(CapabilityPayload payload) {
        snapshot = payload;
    }

    public CapabilityPayload snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshot = empty();
    }

    private static CapabilityPayload empty() {
        return new CapabilityPayload(
                dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION,
                List.of(), false, 0, 0, 0, "");
    }
}
