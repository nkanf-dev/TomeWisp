package dev.tomewisp.net;

/** Minimal cancellation boundary shared by outbound HTTP adapters. */
public interface HttpCancellation {
    boolean isCancelled();

    void onCancel(Runnable listener);
}
