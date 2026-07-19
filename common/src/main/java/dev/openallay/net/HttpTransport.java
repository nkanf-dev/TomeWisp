package dev.openallay.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/** Protocol-neutral HTTP execution; callers retain domain policy and decoding. */
public interface HttpTransport {
    @FunctionalInterface
    interface ResponseDecoder<T> {
        T decode(int status, HttpResponseHeaders headers, InputStream body) throws IOException;
    }

    <T> CompletableFuture<T> execute(
            HttpExchangeRequest request,
            HttpCancellation cancellation,
            ResponseDecoder<T> decoder);
}
