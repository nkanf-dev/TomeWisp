# Direct Rhino Host Objects Design

**Date:** 2026-07-24
**Status:** Accepted for implementation
**Decision:** SKMB-2026-07-24-026
**Supersedes:** the Gson/`JSON.parse` input path in the Rhino Agent Runtime design
**Branch:** `feat/js-agent-runtime`

## Objective

Make the Rhino environment consume the detached Minecraft Java object graph
directly. No request root or workspace value may be serialized into JavaScript
source, parsed back with `JSON.parse`, or eagerly copied into a parallel Rhino
tree.

The result should feel like KubeJS host bindings:

```javascript
return mc.items
  .filter(item => item.tags.includes("minecraft:swords"))
  .map(item => ({id: item.id, damage: item.properties["minecraft:attack_damage"]}))
  .sort((a, b) => b.damage - a.damage)
  .slice(0, 5);
```

Here `mc.items` is a Java-backed read-only collection, each item is the original
detached `RegistryEntrySnapshot`, and nested properties are adapted lazily.

## Considered approaches

### General `NativeJavaObject`

Let Rhino reflect over every public Java member. This is the smallest
implementation, but it makes newly added methods an accidental script API and
would expose class metadata, implementation methods, and potentially
thread-owned objects. It conflicts with OpenAllay's authority boundary.

### Eager `NativeObject`/`NativeArray` conversion

Walk the Java graph once and create a complete Rhino-native copy. This removes
the JSON string but preserves the largest cost: allocating and retaining a
second complete graph before any script knows which fields it needs.

### Lazy OpenAllay host views

Use OpenAllay-owned `Scriptable` implementations that hold direct references to
accepted immutable Java values. Properties and indices adapt only when read,
and one identity cache preserves object identity during the execution. This is
the selected approach because it removes serialization and eager copies without
opening the JVM.

## Components

### `MinecraftAgentHostGraph`

Owns one request's detached input references and top-level root descriptors.
It captures knowledge and extension snapshots once for request consistency.
Registry rows are grouped by kind using lists of original record references.
Recipes and other snapshots remain their existing immutable records.

The graph supports:

```java
Set<String> roots();
Object root(String name);
Map<String, Object> select(Set<String> requested);
List<EvidenceMetadata> evidence();
```

Root selection happens before Rhino wrapping. Enumeration is metadata-only and
does not resolve a root value.

### `RhinoHostAdapter`

Adapts supported Java values into direct script values:

| Java value | Script view |
| --- | --- |
| `null`, primitive wrapper, `String` | primitive |
| enum, UUID, temporal value | stable string |
| `Optional<T>` | adapted value or `undefined` |
| record | `HostRecordView` |
| `List`/`Set` | `HostListView` |
| String-keyed `Map` | `HostMapView` |
| `JsonObject`/`JsonArray` | map/list host view |
| `JsonPrimitive` | primitive |

The adapter caches wrappers in an `IdentityHashMap`. It never uses bean
introspection, public fields, arbitrary methods, `toString` fallback, or
`NativeJavaObject`.

### Record schema cache

Record component metadata is immutable and safe to cache across requests.
`HostRecordSchema` stores only component names and their accessor method
handles. It does not expose the methods themselves to Rhino.

Accessors are invoked lazily. A component value is then passed back through the
same adapter, preserving arbitrary nested record/collection shapes.

### Read-only object view

`HostRecordView` and `HostMapView` use the normal Object prototype and enumerate
only declared component/key names. `get` delegates to the backing Java object;
`put` and `delete` always fail.

### Read-only list view

`HostListView` uses the standard Array prototype, reports `length`, implements
indexed reads and enumeration, and blocks indexed writes/deletes. Array methods
therefore work directly:

- `filter`, `map`, `flatMap`, `reduce`, `find`, `some`, `every`;
- `includes`, `indexOf`, iteration and spreading;
- `slice` creates a normal script array.

Methods that mutate the receiver fail because host writes are forbidden.
Scripts sort a derived array, such as `mc.items.map(...).sort(...)`.

### Direct workspace view

`AgentResultWorkspace.select` supplies retained `JsonElement` references to the
runtime. `workspace.open(handle)` adapts the selected value through
`RhinoHostAdapter`; it does not build or parse JSON text.

### Runtime scope

`RhinoJavascriptRuntime` performs these steps:

1. enter a fresh denied-host Context;
2. initialize safe standard objects;
3. create one `RhinoHostAdapter` for the execution;
4. bind `mc`, `workspace`, and audited helpers as read-only permanent values;
5. evaluate the model function body;
6. normalize only its explicit return value to canonical JSON;
7. discard the scope and adapter cache.

## Extension ABI

`JavascriptDataModule.Snapshot` changes from:

```java
Snapshot(JsonElement value, List<EvidenceMetadata> evidence)
```

to:

```java
Snapshot(Object value, List<EvidenceMetadata> evidence)
```

Values must be detached immutable records or supported immutable collection
graphs. The module is still trusted Java code, but Rhino receives only the
record components and values accepted by `RhinoHostAdapter`.

This lets a mod integration expose its natural Java records without translating
them to Gson or adding a model Tool.

## Result boundary

Canonical JSON remains the result boundary because OpenAllay needs one strict,
provider-neutral representation for:

- evidence validation;
- trace replay;
- bridge transport;
- UI cards;
- compact model text;
- request workspace handles.

This conversion happens once, after the script has already reduced the game
data to an answer-sized value. It does not duplicate the Minecraft input graph.

## Compatibility

The JavaScript surface preserves the existing documented property names and
array behavior. Existing bundled Skill examples remain valid except for
mutating a host collection directly, which was already forbidden by the prior
deep-freeze contract.

Client and server models share the same common binding implementation. A
server-hosted model may still route a client-visible `run_javascript` call back
to the requesting client; the client captures and binds its own detached Java
snapshot before returning canonical output.

## Verification

Focused contract tests will assert direct identity-backed access, lazy root and
field resolution, no input serialization, host immutability, sandbox denial,
extension record support, workspace reopening, and arbitrary nested field
discovery.

Existing runtime, Agent, UI, bridge, Skill, analytical acceptance, Fabric, and
NeoForge suites remain required. A retained benchmark will compare the old
serialized path with the direct path for a large item/recipe graph and verify
that the direct path performs zero input JSON serialization and does not create
a complete duplicate input tree.
