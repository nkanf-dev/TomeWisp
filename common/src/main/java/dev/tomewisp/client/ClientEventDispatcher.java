package dev.tomewisp.client;

@FunctionalInterface
public interface ClientEventDispatcher {
    void execute(Runnable event);
}
