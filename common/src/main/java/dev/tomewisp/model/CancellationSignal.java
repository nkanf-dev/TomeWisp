package dev.tomewisp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSignal implements dev.tomewisp.net.HttpCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> listeners = new ArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new ModelClientException(
                    new ModelFailure("agent_cancelled", "Agent request was cancelled", null));
        }
    }

    public void onCancel(Runnable listener) {
        boolean runNow;
        synchronized (listeners) {
            runNow = cancelled.get();
            if (!runNow) {
                listeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        List<Runnable> snapshot;
        synchronized (listeners) {
            snapshot = List.copyOf(listeners);
            listeners.clear();
        }
        snapshot.forEach(Runnable::run);
        return true;
    }
}
