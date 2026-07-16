package dev.tomewisp.trace.model;

public sealed interface TraceStep permits ToolCallStep, AssistantMessageStep {}
