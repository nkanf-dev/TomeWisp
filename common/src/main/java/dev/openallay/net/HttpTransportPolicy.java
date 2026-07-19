package dev.openallay.net;

import java.time.Duration;

/** Shared connection and decoder policy selected by a domain adapter. */
public record HttpTransportPolicy(
        Duration connectTimeout,
        String decoderThreadName) {
    public HttpTransportPolicy {
        java.util.Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || decoderThreadName == null || decoderThreadName.isBlank()) {
            throw new IllegalArgumentException("invalid HTTP transport policy");
        }
    }
}
