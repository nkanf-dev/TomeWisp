package dev.openallay.tool;

/** A Tool with request-local resources that must be released on every terminal path. */
public interface RequestScopeParticipant {
    void closeRequestScope(String correlationId);
}

