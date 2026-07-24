package dev.openallay.script.host;

import com.google.gson.JsonArray;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import java.util.List;
import java.util.Objects;

/** Array-prototype-compatible, lazy, read-only view over a detached Java sequence. */
public final class HostListView extends ScriptableObject {
    interface Values {
        int size();

        Object get(int index);
    }

    private final RhinoHostAdapter adapter;
    private final Values values;

    private HostListView(
            Context context, Scriptable scope, RhinoHostAdapter adapter, Values values) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.values = Objects.requireNonNull(values, "values");
        setParentScope(scope);
        setPrototype(ScriptableObject.getArrayPrototype(scope, context));
        preventExtensions();
    }

    static HostListView list(
            Context context, Scriptable scope, RhinoHostAdapter adapter, List<?> value) {
        return new HostListView(context, scope, adapter, new Values() {
            @Override public int size() { return value.size(); }
            @Override public Object get(int index) { return value.get(index); }
        });
    }

    static HostListView json(
            Context context, Scriptable scope, RhinoHostAdapter adapter, JsonArray value) {
        return new HostListView(context, scope, adapter, new Values() {
            @Override public int size() { return value.size(); }
            @Override public Object get(int index) { return value.get(index); }
        });
    }

    @Override
    public String getClassName() {
        return "Array";
    }

    public int length() {
        return values.size();
    }

    @Override
    public boolean has(Context context, String name, Scriptable start) {
        return "length".equals(name) || super.has(context, name, start);
    }

    @Override
    public Object get(Context context, String name, Scriptable start) {
        return "length".equals(name) ? values.size() : super.get(context, name, start);
    }

    @Override
    public boolean has(Context context, int index, Scriptable start) {
        return index >= 0 && index < values.size();
    }

    @Override
    public Object get(Context context, int index, Scriptable start) {
        return has(context, index, start)
                ? adapter.adapt(values.get(index))
                : Scriptable.NOT_FOUND;
    }

    @Override
    public Object[] getIds(Context context) {
        Object[] ids = new Object[values.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = index;
        }
        return ids;
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
