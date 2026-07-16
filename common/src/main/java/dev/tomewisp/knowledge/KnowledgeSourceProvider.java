package dev.tomewisp.knowledge;

public interface KnowledgeSourceProvider {
    String sourceId();

    KnowledgeLoad load() throws Exception;
}
