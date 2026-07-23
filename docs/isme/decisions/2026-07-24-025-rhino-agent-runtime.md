# SKMB-2026-07-24-025: Rhino Agent Runtime, Request Workspace, and Managed Skills

- Status: accepted
- Date: 2026-07-24
- Scope: post-0.1.0 Agent tool architecture
- Patterns: A, B, C, D, E, F, G

## Decision basis

```yaml
decision_basis:
  decision_id: SKMB-2026-07-24-025
  trigger: >-
    Real long-running provider sessions showed that a growing collection of
    domain Tools forced repeated narrow calls, inflated context with structured
    payloads, and could not express cross-domain ranking and transformation
    naturally. The attempted Resource VFS architecture was explicitly rejected
    in favor of a general embedded JavaScript analysis runtime.
  owner: GameGuideAgent, Rhino runtime, request workspace, Skill repository
  lifecycle: one captured Agent request, plus explicitly managed local Skill files
  concurrency_scope: one isolated Rhino Context and workspace per request correlation ID
  selected_defaults:
    engine: KubeJS-Mods Rhino fork embedded as a shaded runtime dependency
    isolation: one Context and one detached immutable data graph per execution
    standard_library: Rhino safe standard objects plus OpenAllay-owned bindings
    model_tools:
      - openallay:run_javascript
      - openallay:load_skill
      - openallay:manage_skill
    large_results: retain canonical JSON in a request workspace and return a handle, schema, cardinality, and bounded preview
    continuation: later scripts reopen prior results by opaque handle and transform them without copying the full result into model context
    time_budget: two seconds of wall-clock execution, checked by Rhino instruction observation and cancellation
    skill_writes: local managed directory only, strict Agent Skills subset, atomic replace, explicit create/update/delete operation
    extension_bridge: code-registered read-only adapters contribute detached values and helper modules
  authority:
    - user explicitly rejected the VFS architecture and requested embedded JavaScript
    - user explicitly selected KubeJS Rhino as the implementation reference
    - user delegated detailed architecture and implementation decisions
    - user explicitly requested Agent-created, updated, and deleted Skills
  forbidden:
    - arbitrary Java class loading, Packages, JavaAdapter, reflection, ClassLoader access, or unrestricted host-object wrapping
    - network access, process execution, shell syntax, real filesystem access, arbitrary paths, or native library loading
    - live Minecraft objects on model or script worker threads
    - world, inventory, configuration, command, or registry mutation
    - sharing a mutable Rhino scope between requests or sessions
    - treating a workspace summary or preview as complete factual evidence
```

## Context and supersession

This decision supersedes the “scripts are unrepresentable” part of
SKMB-2026-07-19-024/I80. It does not weaken the immutable snapshot, evidence,
thread-ownership, authority, or fail-closed requirements from earlier
decisions. JavaScript is a controlled analysis language over already captured
data, not a new authority source.

The implementation follows the useful seams in KubeJS and its Rhino fork:

- a dedicated `ContextFactory`/`Context` rather than a global evaluator;
- explicit bindings and type conversion rather than exposing the JVM;
- safe standard objects and sealed host capabilities;
- instruction observation for cancellation and execution limits;
- class visibility and reflection denial at the runtime boundary.

OpenAllay does not depend on KubeJS itself. It embeds the
[`KubeJS-Mods/Rhino`](https://github.com/KubeJS-Mods/Rhino) fork and owns a
smaller, read-only binding layer suitable for model-authored programs.

## Selected behavior

### Runtime ownership and execution

`run_javascript` receives source text and executes it on a virtual worker
thread. It creates a fresh Rhino Context and scope for that invocation. The
scope contains safe ECMAScript built-ins and immutable OpenAllay bindings only.
The final expression or explicit `return` value becomes the canonical result.

The instruction observer checks both the Agent cancellation signal and a
two-second wall-clock deadline. Cancellation wins over timeout. A timed-out or
cancelled execution publishes no successful workspace value and the Context is
discarded.

Scripts cannot retain Java objects or Rhino scopes. Any accepted result is
normalized immediately into the repository Gson `JsonElement` tree. Unsupported
values, cycles, functions, promises, symbols, host objects, and non-finite
numbers fail explicitly.

### Injected Minecraft data

The `mc` binding is assembled from the request's detached
`ToolInvocationContext`. It includes every captured category that can be
represented without crossing authority or thread boundaries:

- caller and evidence metadata;
- player identity, inventory, and client-visible state;
- registry entries and their codec-derived properties/components;
- recipes, ingredients, outputs, processing metadata, and provenance;
- observable settings, packs, mods, diagnostics, position, and query results;
- loaded knowledge metadata where it is part of the captured request.

Missing categories are absent and listed in `mc.capabilities`; they are never
fabricated as empty authoritative datasets. Stable IDs and evidence fields are
preserved through transformations.

The ergonomic surface is JavaScript-native. Arrays support `filter`, `map`,
`reduce`, `sort`, `flatMap`, grouping helpers, and user-defined functions. The
runtime also exposes small deterministic helper modules for common operations,
but those helpers are ordinary JS/library functions rather than additional
model Tool IDs.

### Request workspace and model projection

Every successful result is stored as canonical JSON in a workspace owned by the
request correlation ID. Handles are opaque and cannot be constructed from
paths. A later `run_javascript` call in the same request may use
`workspace.open(handle)` to transform the prior value.

The model-facing result is a projection, not the canonical JSON:

- scalar and compact values are rendered directly;
- collections report type, cardinality, discovered field paths, and a concise
  preview;
- larger values return the same metadata plus a workspace handle;
- evidence/provenance required for factual use remains present in the
  projection;
- omission is explicit, with instructions to reopen, filter, aggregate, or
  project the stored result.

The workspace is closed on request completion, failure, cancellation,
disconnect, or shutdown. Handles from another request or a closed workspace
fail `workspace_handle_unavailable`.

### Extension adapters

Community or mod integrations register an `AgentDataAdapter` in Java. An
adapter runs during Minecraft-owned capture, converts public API values into
detached JSON-compatible records, declares authority and evidence, and mounts
them under a stable `mc.extensions.<namespace>` key.

An adapter may also contribute a pure helper module implemented by OpenAllay or
extension code. Helper modules receive only detached values. Ordinary Agent
scripts never receive `Class`, `Method`, `Field`, `AccessibleObject`,
`ClassLoader`, a Minecraft live object, or a general Java bridge.

### Managed Skills

Bundled Skills remain immutable. `manage_skill` may create, update, or delete
only a local package below the OpenAllay managed Skills root. Names and relative
reference paths use the existing strict Agent Skills subset.

Create/update stages a complete package, parses every file, validates metadata,
references, and allowed Tool dependencies, then atomically replaces the target
directory. Delete resolves one exact managed Skill name and removes only that
package. A failed validation or write leaves the previously active Skill
unchanged. The active request retains its captured Skill catalog; changes apply
to later requests.

## Failure semantics

- Syntax or runtime errors return `javascript_error` with a bounded,
  credential-free location summary.
- Cancellation returns the existing Agent cancellation outcome and stores no
  result.
- The instruction deadline returns `javascript_timeout`.
- Unsupported/cyclic/non-finite output returns `javascript_result_invalid`.
- A missing or foreign result handle returns `workspace_handle_unavailable`.
- Missing captured data remains explicit in capabilities and cannot be
  converted into a successful empty fact.
- Adapter failure marks only that extension dataset unavailable and preserves
  all independent datasets.
- Managed Skill validation fails `skill_invalid`; confinement or atomic write
  failures return `skill_write_failed`; the prior active package remains.

## Required evidence

Deterministic coverage must prove:

- safe standard JavaScript data transforms;
- denial of Java/class/reflection/filesystem/network/process access;
- cancellation, timeout, and scope disposal;
- canonical JSON normalization and cyclic/unsupported rejection;
- handle isolation, reuse, and request cleanup;
- compact scalar output and large-result projection without full context copy;
- generic discovery of mod-added nested fields;
- atomic Skill create/update/delete with rollback and future-request visibility;
- unchanged Fabric/NeoForge common-core boundaries.

The acceptance scenarios are:

1. find the highest-damage sword with one analysis execution;
2. identify the strongest poison-causing obtainable item and trace its
   production path using component/effect and recipe data;
3. find the craftable container recipe requiring the fewest materials.

