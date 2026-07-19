package dev.openallay.guide;

@FunctionalInterface
public interface GuideSubscription extends AutoCloseable {
    @Override
    void close();
}
