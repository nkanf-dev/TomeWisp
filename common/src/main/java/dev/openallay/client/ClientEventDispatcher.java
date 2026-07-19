package dev.openallay.client;

@FunctionalInterface
public interface ClientEventDispatcher {
    void execute(Runnable event);
}
