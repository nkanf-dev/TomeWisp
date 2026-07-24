# SKMB-2026-07-24-026: Direct Rhino Host Objects

- Status: accepted
- Date: 2026-07-24
- Scope: Rhino input binding, request host graph, workspace reopening, and extension data ABI
- Patterns: C, E, F
- Supersedes: the Gson-tree input projection and `JSON.parse` binding selected by SKMB-2026-07-24-025

## Decision basis

```yaml
decision_basis:
  decision_id: SKMB-2026-07-24-026
  trigger: >-
    The implemented Rhino runtime first copied the complete detached request
    into Gson, serialized selected data into JavaScript source, and parsed it
    again inside Rhino. The designer explicitly rejected this loss and required
    the ideal KubeJS-style path in which Rhino reads Java objects directly.
  owner: RhinoJavascriptRuntime, request host graph, extension modules, request workspace
  lifecycle: one immutable host graph and identity cache per active request; one fresh Rhino scope per execution
  concurrency_scope: request correlation ID
  selected_behavior:
    input_binding: lazy read-only Scriptable views over original detached Java values
    supported_values:
      - records
      - immutable List and Set values
      - String-keyed immutable Map values
      - Optional values
      - primitives, strings, enums, UUIDs, and temporal values
      - existing immutable Gson JsonElement leaves supplied by captured codec data
    root_selection: expose only selected top-level roots; root enumeration never materializes values
    identity: repeated access to one Java object returns the same request-scope Scriptable wrapper
    arrays: Java collections are read-only array-like host views with the standard Array prototype
    records: only record component names are properties; Java methods and class metadata are absent
    workspace: reopen canonical results through the same direct host adapter without serialization
    extensions: registered modules return detached Java values directly with evidence
    output_boundary: normalize only the explicit script return value into canonical JSON
  authority:
    - designer instruction: "做到最完美的理想方案，不要损耗，直接暴露 java 对象"
    - designer previously selected KubeJS Rhino as the implementation reference
    - repository invariants require live Minecraft state to remain on its owning thread
    - repository invariants forbid an unrestricted reflection or host-class surface
  forbidden:
    - serializing the request graph or workspace values into JavaScript source
    - parsing request input with JavaScript JSON.parse
    - eagerly copying a complete host graph into NativeObject or NativeArray
    - exposing Class, ClassLoader, AccessibleObject, arbitrary methods, bean getters, fields, or constructors
    - exposing live Minecraft, registry, recipe-manager, UI, level, player, or resource-manager objects
    - writable host collections or host property deletion
    - cross-request wrapper, scope, host graph, or workspace reuse
```

## Selected behavior

### Request host graph

The owning Minecraft thread still captures live state into immutable
`ToolInvocationContext` records. The Agent worker creates one
`MinecraftAgentHostGraph` for the request. The graph retains references to those
detached records and to one captured knowledge/extension snapshot; it does not
convert them into a second generic object tree.

Top-level roots are descriptors backed by suppliers. Selecting `items` exposes
only the item view and small request metadata. Asking for root names enumerates
descriptors without resolving their values. Registry kind views group references
to existing `RegistryEntrySnapshot` records; recipe and observable-state views
retain their existing immutable lists and records.

### Rhino host adapter

`RhinoHostAdapter` is the sole Java-to-script conversion authority. It converts
primitive scalar values directly and wraps accepted aggregate values in
OpenAllay-owned `Scriptable` implementations:

- record components become read-only JavaScript properties;
- `List` and `Set` become read-only array-like objects with the standard Array
  prototype, so non-mutating `filter`, `map`, `reduce`, `flatMap`, `includes`,
  iteration, indexing, and `length` work normally;
- String-keyed maps and Gson objects become read-only property bags;
- Gson arrays use the same array-like view;
- Optional empty values become JavaScript `undefined`;
- enums, identifiers, and temporal values expose their stable textual value.

Wrappers use an identity cache owned by one Rhino execution. Child properties
are adapted only when read. Wrapper implementations expose no Java method
surface and do not implement Rhino `Wrapper`, so a returned host value cannot be
unwrapped by model-authored code.

All writes, deletes, index assignments, extension, and mutation through host
views fail explicitly as `javascript_host_read_only`. JavaScript-created arrays
and objects remain ordinary mutable script values.

### Execution

`RhinoJavascriptRuntime` creates safe standard objects, installs audited helper
functions, defines `mc` and `workspace` as read-only permanent bindings, and
evaluates only the model-authored function body. It never embeds Minecraft data
or workspace data in source text.

The instruction deadline, cancellation, result graph budgets, and class
visibility denial remain unchanged. The context and wrapper identity cache are
discarded after normalization.

### Extensions

`JavascriptDataModule.Snapshot` accepts a detached Java value instead of a
`JsonElement`. A module may return records and immutable collection graphs from
the request snapshot. Unsupported or thread-owned values fail that module
independently and appear in extension diagnostics. Module evidence remains
mandatory.

Modules do not inject executable functions. Reusable pure behavior remains an
audited runtime helper or a Skill-provided JavaScript function.

### Output and workspace

Only a script's explicit return value crosses into canonical JSON. That
normalization is intentional: it supplies the stable validation, evidence,
trace, UI, model-projection, bridge, and request-workspace format. It is not
used to construct `mc`.

The workspace remains request-local canonical JSON, but reopening a handle
wraps its stored `JsonElement` directly. It is never serialized and parsed
inside Rhino.

## States and transitions

- `host_graph_ready`: immutable request records and root descriptors are bound
  to one correlation ID.
- `host_scope_open`: one Rhino execution owns a fresh wrapper identity cache.
- `host_scope_disposed`: the execution scope and wrapper cache are unreachable.

`tool_wait -> host_graph_ready` occurs once on the first JavaScript call for a
request. Each execution transitions `host_graph_ready -> host_scope_open ->
host_graph_ready`. Request completion, failure, cancellation, disconnect, or
shutdown transitions to terminal cleanup and invalidates the host graph and
workspace.

## Failure semantics

- An unsupported Java value reached through a registered root fails
  `javascript_host_type_unsupported`; it is never stringified reflectively.
- A map with a non-String key fails `javascript_host_map_key_unsupported`.
- Any host write/delete fails `javascript_host_read_only` and stores no result.
- A module whose capture throws is omitted with `module_capture_failed`;
  independent roots and modules remain usable. A successfully captured module
  may retain arbitrary nested detached values, but accessing an unsupported
  nested value fails locally with `javascript_host_type_unsupported` rather
  than eagerly walking or discarding the module.
- A host graph or handle from another/closed request remains unavailable.
- Cancellation or timeout discards the current scope and wrapper cache and
  publishes no handle.

## Required evidence

Tests must prove:

1. request and workspace input execute without `Gson.toJson`, `JsonElement`
   projection, or `JSON.parse`;
2. direct record/list/map/Optional/enum/temporal/JsonElement access preserves
   every field and arbitrary mod-added nested property;
3. array transforms operate over Java-backed views without eagerly copying
   every row;
4. record methods, `getClass`, constructors, fields, reflection, class loading,
   and arbitrary Java objects are unrepresentable;
5. host writes and deletes fail and cannot mutate the detached request;
6. root selection prevents unselected root resolution;
7. extension records are queryable directly and one failed module degrades
   independently;
8. workspace handles reopen directly and remain request-isolated;
9. the three analytical Agent scenarios and both loader builds still pass.
