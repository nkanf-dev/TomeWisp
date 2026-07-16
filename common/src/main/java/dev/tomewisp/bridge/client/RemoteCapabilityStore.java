package dev.tomewisp.bridge.client;

import dev.tomewisp.bridge.protocol.CapabilityPayload;
import java.util.List;

public final class RemoteCapabilityStore {
    private volatile CapabilityPayload snapshot =
            new CapabilityPayload(dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION, List.of(), false);

    public void replace(CapabilityPayload payload) {
        snapshot = payload;
    }

    public CapabilityPayload snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshot = new CapabilityPayload(
                dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION, List.of(), false);
    }
}
