package dev.openallay.script.host;

import com.google.gson.JsonObject;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lazy component/key-only view over one detached Java record, map, or Gson object. */
public final class HostObjectView extends ScriptableObject {
    @FunctionalInterface
    interface Reader {
        Object read(String name);
    }

    private final RhinoHostAdapter adapter;
    private final List<String> keys;
    private final java.util.Set<String> keySet;
    private final Reader reader;

    private HostObjectView(
            Context context,
            Scriptable scope,
            RhinoHostAdapter adapter,
            List<String> keys,
            Reader reader) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.keys = List.copyOf(keys);
        this.keySet = java.util.Set.copyOf(keys);
        this.reader = Objects.requireNonNull(reader, "reader");
        setParentScope(scope);
        setPrototype(ScriptableObject.getObjectPrototype(scope, context));
        preventExtensions();
    }

    static HostObjectView record(
            Context context, Scriptable scope, RhinoHostAdapter adapter, Object value) {
        HostRecordSchema schema = HostRecordSchema.of(value.getClass());
        return new HostObjectView(
                context,
                scope,
                adapter,
                schema.names(),
                name -> {
                    Object result = schema.read(value, name);
                    return result == HostRecordSchema.Missing.INSTANCE
                            ? Scriptable.NOT_FOUND : result;
                });
    }

    static HostObjectView map(
            Context context, Scriptable scope, RhinoHostAdapter adapter, Map<?, ?> value) {
        ArrayList<String> keys = new ArrayList<>(value.size());
        for (Object key : value.keySet()) {
            if (!(key instanceof String string)) {
                throw HostAccessException.unsupportedMapKey(key);
            }
            keys.add(string);
        }
        return new HostObjectView(
                context,
                scope,
                adapter,
                keys,
                name -> value.containsKey(name) ? value.get(name) : Scriptable.NOT_FOUND);
    }

    static HostObjectView json(
            Context context, Scriptable scope, RhinoHostAdapter adapter, JsonObject value) {
        return new HostObjectView(
                context,
                scope,
                adapter,
                List.copyOf(value.keySet()),
                name -> value.has(name) ? value.get(name) : Scriptable.NOT_FOUND);
    }

    @Override
    public String getClassName() {
        return "Object";
    }

    @Override
    public boolean has(Context context, String name, Scriptable start) {
        return keySet.contains(name) || super.has(context, name, start);
    }

    @Override
    public Object get(Context context, String name, Scriptable start) {
        if (!keySet.contains(name)) {
            return super.get(context, name, start);
        }
        Object value = reader.read(name);
        return value == Scriptable.NOT_FOUND ? value : adapter.adapt(value);
    }

    @Override
    public boolean has(Context context, int index, Scriptable start) {
        return keySet.contains(Integer.toString(index));
    }

    @Override
    public Object get(Context context, int index, Scriptable start) {
        String name = Integer.toString(index);
        if (!keySet.contains(name)) {
            return Scriptable.NOT_FOUND;
        }
        Object value = reader.read(name);
        return value == Scriptable.NOT_FOUND ? value : adapter.adapt(value);
    }

    @Override
    public Object[] getIds(Context context) {
        return keys.toArray();
    }

    @Override
    public void put(Context context, String name, Scriptable start, Object value) {
        throw HostAccessException.readOnly();
    }

    @Override
    public void put(Context context, int index, Scriptable start, Object value) {
        throw HostAccessException.readOnly();
    }

    @Override
    public void delete(Context context, String name) {
        throw HostAccessException.readOnly();
    }

    @Override
    public void delete(Context context, int index) {
        throw HostAccessException.readOnly();
    }
}
