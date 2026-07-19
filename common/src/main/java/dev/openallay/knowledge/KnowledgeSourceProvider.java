package dev.openallay.knowledge;

public interface KnowledgeSourceProvider {
    String sourceId();

    KnowledgeLoad load() throws Exception;
}
