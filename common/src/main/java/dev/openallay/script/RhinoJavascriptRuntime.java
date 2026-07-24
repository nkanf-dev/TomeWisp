package dev.openallay.script;

import com.google.gson.JsonElement;
import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClientException;
import dev.openallay.script.host.RhinoHostAdapter;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class RhinoJavascriptRuntime {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private static final String HELPERS = """
            const __openallayGuardedStringSize = value => {
              const size = Number(value);
              if (!Number.isFinite(size) || size < 0 || size > 524288) {
                throw new Error("javascript_result_budget_exceeded: requested string is too large");
              }
              return size;
            };
            const __nativeRepeat = String.prototype.repeat;
            const __nativePadStart = String.prototype.padStart;
            const __nativePadEnd = String.prototype.padEnd;
            const __openallayArrayView = value =>
              Array.isArray(value)
              || (value !== null
                && typeof value === "object"
                && Object.getPrototypeOf(value) === Array.prototype
                && Number.isSafeInteger(Number(value.length)));
            Object.defineProperty(String.prototype, "repeat", {
              value(count) { return __nativeRepeat.call(this, __openallayGuardedStringSize(count)); }
            });
            Object.defineProperty(String.prototype, "padStart", {
              value(length, fill) { return __nativePadStart.call(this, __openallayGuardedStringSize(length), fill); }
            });
            Object.defineProperty(String.prototype, "padEnd", {
              value(length, fill) { return __nativePadEnd.call(this, __openallayGuardedStringSize(length), fill); }
            });
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
              },
              schema(value, depth = 3) {
                const visit = (current, remaining) => {
                  if (current === null) return "null";
                  if (__openallayArrayView(current)) {
                    if (remaining <= 0 || current.length === 0) return [];
                    const samples = current.slice(0, 8).map(item => visit(item, remaining - 1));
                    return samples.filter((sample, index) =>
                      index === samples.findIndex(other => JSON.stringify(other) === JSON.stringify(sample)));
                  }
                  if (typeof current !== "object") return typeof current;
                  if (remaining <= 0) return "object";
                  return Object.fromEntries(Object.keys(current).sort()
                    .map(key => [key, visit(current[key], remaining - 1)]));
                };
                return visit(value, Math.max(0, Number(depth) || 0));
              }
            });
            """;

    private final Duration timeout;
    private final JavascriptRuntimeLimits limits;
    private final RhinoJsonNormalizer normalizer;

    public RhinoJavascriptRuntime() {
        this(DEFAULT_TIMEOUT, JavascriptRuntimeLimits.DEFAULT);
    }

    public RhinoJavascriptRuntime(Duration timeout) {
        this(timeout, JavascriptRuntimeLimits.DEFAULT);
    }

    public RhinoJavascriptRuntime(Duration timeout, JavascriptRuntimeLimits limits) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.normalizer = new RhinoJsonNormalizer(limits);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public JavascriptExecution execute(
            String source,
            Map<String, Object> minecraftRoots,
            Map<String, JsonElement> workspaceValues,
            CancellationSignal cancellation) {
        if (source == null || source.isBlank()) {
            throw new JavascriptExecutionException(
                    "javascript_invalid", "JavaScript source must not be blank");
        }
        if (source.length() > limits.maxSourceCharacters()) {
            throw new JavascriptExecutionException(
                    "javascript_source_too_large",
                    "JavaScript source exceeds the execution budget");
        }
        Objects.requireNonNull(minecraftRoots, "minecraftRoots");
        Objects.requireNonNull(workspaceValues, "workspaceValues");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();

        long started = System.nanoTime();
        OpenAllayRhinoContextFactory factory =
                new OpenAllayRhinoContextFactory(cancellation, timeout);
        Context context = factory.enter();
        try {
            ScriptableObject scope = context.initSafeStandardObjects(null, false);
            RhinoHostAdapter adapter = new RhinoHostAdapter(context, scope);
            defineGlobal(context, scope, "mc", adapter.adapt(minecraftRoots));
            defineGlobal(
                    context,
                    scope,
                    "workspace",
                    workspace(context, scope, adapter, workspaceValues));
            String program = buildProgram(source);
            Object value = context.evaluateString(scope, program, "openallay-agent.js", 1, null);
            return new JavascriptExecution(
                    normalizer.normalize(value, context),
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

    private static String buildProgram(String source) {
        return """
                (function() {
                  "use strict";
                  %s
                  return (function() {
                    "use strict";
                    %s
                  })();
                })()
                """.formatted(HELPERS, source);
    }

    private static void defineGlobal(
            Context context, ScriptableObject scope, String name, Object value) {
        ScriptableObject.defineProperty(
                scope,
                name,
                value,
                ScriptableObject.READONLY
                        | ScriptableObject.PERMANENT
                        | ScriptableObject.DONTENUM,
                context);
    }

    private static Scriptable workspace(
            Context context,
            ScriptableObject scope,
            RhinoHostAdapter adapter,
            Map<String, JsonElement> values) {
        Scriptable workspace = context.newObject(scope);
        BaseFunction open = new BaseFunction(
                scope, ScriptableObject.getFunctionPrototype(scope, context)) {
            @Override
            public String getFunctionName() {
                return "open";
            }

            @Override
            public Object call(
                    Context callContext,
                    Scriptable callScope,
                    Scriptable thisObject,
                    Object[] arguments) {
                if (arguments.length != 1 || !(arguments[0] instanceof CharSequence handle)) {
                    throw new JavascriptExecutionException(
                            "workspace_handle_unavailable",
                            "workspace.open requires one selected result handle");
                }
                JsonElement value = values.get(handle.toString());
                if (value == null) {
                    throw new JavascriptExecutionException(
                            "workspace_handle_unavailable",
                            "Result handle is unavailable in this execution");
                }
                return adapter.adapt(value);
            }

            @Override
            public Scriptable construct(
                    Context callContext, Scriptable callScope, Object[] arguments) {
                throw new JavascriptExecutionException(
                        "javascript_host_access_denied",
                        "Host functions are not constructors");
            }
        };
        ScriptableObject.defineProperty(
                workspace,
                "open",
                open,
                ScriptableObject.READONLY | ScriptableObject.PERMANENT,
                context);
        if (workspace instanceof ScriptableObject object) {
            object.preventExtensions();
        }
        return workspace;
    }

    private static String summarize(RhinoException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = "JavaScript evaluation failed";
        }
        return message.length() <= 320 ? message : message.substring(0, 320);
    }
}
