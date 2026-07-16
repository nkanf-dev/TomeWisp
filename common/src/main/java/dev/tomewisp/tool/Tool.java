package dev.tomewisp.tool;

public interface Tool<I, O> {
    ToolDescriptor<I, O> descriptor();

    ToolResult<O> invoke(I input);
}
