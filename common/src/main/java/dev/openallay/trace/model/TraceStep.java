package dev.openallay.trace.model;

public sealed interface TraceStep permits ToolCallStep, AssistantMessageStep {}
