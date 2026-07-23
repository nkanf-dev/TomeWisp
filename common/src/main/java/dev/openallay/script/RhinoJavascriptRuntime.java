package dev.openallay.script;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClientException;
import java.time.Duration;
import java.util.Objects;

public final class RhinoJavascriptRuntime {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private static final String HELPERS = """
            const helpers = Object.freeze({
              groupBy(values, key) {
                return values.reduce((groups, value) => {
                  const group = String(key(value));
                  (groups[group] ??= []).push(value);
                  return groups;
                }, {});
              },
              sum(values, select = value => value) {
                return values.reduce((total, value) => total + Number(select(value)), 0);
              },
              minBy(values, select) {
                return values.reduce((best, value) =>
                  best === undefined || select(value) < select(best) ? value : best, undefined);
              },
              maxBy(values, select) {
                return values.reduce((best, value) =>
                  best === undefined || select(value) > select(best) ? value : best, undefined);
              }
            });
            """;

    private final Gson gson;
    private final Duration timeout;
    private final RhinoJsonNormalizer normalizer = new RhinoJsonNormalizer();

    public RhinoJavascriptRuntime(Gson gson) {
        this(gson, DEFAULT_TIMEOUT);
    }

    public RhinoJavascriptRuntime(Gson gson, Duration timeout) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public JavascriptExecution execute(
            String source,
            JsonElement minecraftData,
            JsonObject workspaceValues,
            CancellationSignal cancellation) {
        if (source == null || source.isBlank()) {
            throw new JavascriptExecutionException(
                    "javascript_invalid", "JavaScript source must not be blank");
        }
        Objects.requireNonNull(minecraftData, "minecraftData");
        Objects.requireNonNull(workspaceValues, "workspaceValues");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();

        long started = System.nanoTime();
        OpenAllayRhinoContextFactory factory =
                new OpenAllayRhinoContextFactory(cancellation, timeout);
        Context context = factory.enter();
        try {
            ScriptableObject scope = context.initSafeStandardObjects(null, false);
            String program = buildProgram(source, minecraftData, workspaceValues);
            Object value = context.evaluateString(scope, program, "openallay-agent.js", 1, null);
            return new JavascriptExecution(
                    normalizer.normalize(value),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (JavascriptExecutionException failure) {
            throw failure;
        } catch (ModelClientException cancellationFailure) {
            throw cancellationFailure;
        } catch (RhinoException failure) {
            throw new JavascriptExecutionException(
                    "javascript_error", summarize(failure), failure);
        } catch (RuntimeException failure) {
            throw new JavascriptExecutionException(
                    "javascript_error", "JavaScript execution failed", failure);
        }
    }

    private String buildProgram(
            String source, JsonElement minecraftData, JsonObject workspaceValues) {
        String mcJsonLiteral = gson.toJson(minecraftData.toString());
        String workspaceJsonLiteral = gson.toJson(workspaceValues.toString());
        return """
                (function() {
                  "use strict";
                  const __deepFreeze = value => {
                    if (value && typeof value === "object" && !Object.isFrozen(value)) {
                      Object.freeze(value);
                      Object.keys(value).forEach(key => __deepFreeze(value[key]));
                    }
                    return value;
                  };
                  const mc = __deepFreeze(JSON.parse(%s));
                  const __workspaceValues = __deepFreeze(JSON.parse(%s));
                  const workspace = Object.freeze({
                    open(handle) {
                      if (!Object.hasOwn(__workspaceValues, handle)) {
                        throw new Error("workspace_handle_unavailable: " + handle);
                      }
                      return __workspaceValues[handle];
                    }
                  });
                  %s
                  return (function() {
                    "use strict";
                    %s
                  })();
                })()
                """.formatted(mcJsonLiteral, workspaceJsonLiteral, HELPERS, source);
    }

    private static String summarize(RhinoException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = "JavaScript evaluation failed";
        }
        return message.length() <= 320 ? message : message.substring(0, 320);
    }
}

