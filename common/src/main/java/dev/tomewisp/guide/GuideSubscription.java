package dev.tomewisp.guide;

@FunctionalInterface
public interface GuideSubscription extends AutoCloseable {
    @Override
    void close();
}
