package dev.openallay.tool;

import java.util.Collection;

public interface ToolProvider {
    String providerId();

    Collection<? extends Tool<?, ?>> tools();
}
