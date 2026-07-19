package dev.openallay.bridge.protocol;

public final class BridgeProtocol {
    public static final int VERSION = 6;
    /** Raw bytes per Base64 chunk, leaving room below Minecraft's 32,767-char string cap. */
    public static final int TRANSPORT_CHUNK_BYTES = 16 * 1024;
    public static final java.time.Duration PARTIAL_ASSEMBLY_TIMEOUT =
            java.time.Duration.ofMinutes(5);

    private BridgeProtocol() {}

    public static void requireVersion(int version) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported OpenAllay bridge version " + version + "; expected " + VERSION);
        }
    }
}
