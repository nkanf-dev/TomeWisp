package dev.tomewisp.bridge.protocol;

public final class BridgeProtocol {
    public static final int VERSION = 4;

    private BridgeProtocol() {}

    public static void requireVersion(int version) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported TomeWisp bridge version " + version + "; expected " + VERSION);
        }
    }
}
