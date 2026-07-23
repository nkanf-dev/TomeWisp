package dev.openallay.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.openallay.model.CancellationSignal;
import java.lang.reflect.AccessibleObject;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Rhino context for model-authored analysis. It intentionally exposes no JVM
 * class surface; Minecraft data is injected as detached JSON.
 */
final class OpenAllayRhinoContext extends Context {
    private static final int OBSERVER_THRESHOLD = 2_000;

    private final CancellationSignal cancellation;
    private final long deadlineNanos;

    OpenAllayRhinoContext(
            ContextFactory factory, CancellationSignal cancellation, Duration timeout) {
        super(factory);
        this.cancellation = cancellation;
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
        setInstructionObserverThreshold(OBSERVER_THRESHOLD);
    }

    @Override
    public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
        return false;
    }

    @Override
    public Scriptable wrapAsJavaObject(
            Scriptable scope, Object javaObject, TypeInfo target) {
        if (javaObject instanceof Class<?>
                || javaObject instanceof AccessibleObject
                || javaObject instanceof ClassLoader
                || javaObject instanceof Thread
                || javaObject instanceof Process
                || javaObject instanceof java.io.File
                || javaObject instanceof Path
                || javaObject instanceof java.net.URL
                || javaObject instanceof java.net.URI
                || javaObject instanceof java.net.Socket) {
            throw new JavascriptExecutionException(
                    "javascript_host_access_denied",
                    "Java host access is not available in the OpenAllay runtime");
        }
        throw new JavascriptExecutionException(
                "javascript_host_access_denied",
                "Java host objects are not available in the OpenAllay runtime");
    }

    @Override
    protected void observeInstructionCount(int instructionCount) {
        cancellation.throwIfCancelled();
        if (Thread.currentThread().isInterrupted()) {
            throw new JavascriptExecutionException(
                    "javascript_cancelled", "JavaScript execution was interrupted");
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw new JavascriptExecutionException(
                    "javascript_timeout", "JavaScript execution exceeded its time budget");
        }
    }
}

