# OpenAllay Rhino Agent Runtime Design

**Date:** 2026-07-24  
**Status:** Approved for implementation  
**Decision:** SKMB-2026-07-24-025  
**Baseline:** `v0.1.0-SNAPSHOT` (`a6b738b`)  
**Branch:** `codex/js-agent-runtime`

## Objective

Replace the growing domain-specific Agent Tool surface with a compact,
composable JavaScript runtime that can analyze Minecraft data directly.

This is not a VFS under a different name. The main abstraction is an isolated
programming environment:

- Minecraft information is injected as detached JavaScript data.
- The model composes ordinary JavaScript transformations.
- Large intermediate values remain in a request workspace.
- Skills teach domain workflows and reusable scripts progressively.
- Extensions add data adapters and helper modules instead of model Tool IDs.

The implementation follows KubeJS's use of the
[`KubeJS-Mods/Rhino`](https://github.com/KubeJS-Mods/Rhino) fork: dedicated
contexts, bindings, conversions, instruction observation, and host-access
filtering. OpenAllay embeds Rhino directly and does not require KubeJS.

## Why the existing Tool model is insufficient

The 0.1.0 Agent owns typed Tools for recipes, uses, inventory, guides, game
state, resource resolution, and craftability. This is reliable for known
single-domain questions, but creates three scaling problems:

1. Cross-domain analysis becomes a sequence of narrow round trips.
2. Every new field or mod concept pressures OpenAllay to add another schema or
   Tool.
3. Large normalized Tool results enter model context even when only an
   aggregate or a few rows are needed.

A Unix-like VFS would improve discovery but still makes the model express
analysis indirectly through path and text conventions. JavaScript supplies the
missing primitive: selection, transformation, joins, grouping, aggregation,
reusable functions, and domain-neutral data handling.

## Model-facing surface

### `openallay:run_javascript`

Input contains one `source` string. Available globals are:

- `mc`: current detached Minecraft data graph;
- `workspace.open(handle)`: prior results from this request;
- `helpers`: pure deterministic utilities;
- safe standard JavaScript built-ins.

Example:

```javascript
return mc.items
  .filter(x => x.tags.includes("minecraft:swords"))
  .sort((a, b) => b.damage - a.damage)[0]
```

The canonical result remains internal JSON. The model receives a compact
evidence-preserving projection with type, cardinality, discovered fields,
preview, omission state, and an opaque continuation handle.

### `openallay:load_skill`

The existing progressively disclosed Skill reader remains. Its catalog is
rewritten around analytical tasks and runtime techniques rather than old domain
Tool names.

### `openallay:manage_skill`

Input selects `create`, `update`, or `delete`, an exact Skill name, and complete
managed package files. It never accepts an arbitrary filesystem path.

## Runtime architecture

### Dependency

Use `dev.latvian.mods:rhino:2101.2.8-build.91` from the official Latvian Maven
repository. The version is pinned and packaged inside both loader artifacts.
Rhino upgrades require focused sandbox and loader packaging tests.

### Context factory

`OpenAllayRhinoContextFactory` creates a fresh `OpenAllayRhinoContext` for every
invocation. The context:

- starts from `initSafeStandardObjects`;
- uses interpreted mode so instruction observation is effective;
- denies Java class visibility and Java package access;
- rejects wrapping classes, reflection, class loaders, threads, files, paths,
  sockets, URLs, processes, and live Minecraft objects;
- converts only detached JSON-compatible values and registered safe facades.

Top-level host bindings are read-only. The scope is discarded immediately
after result normalization.

### Cancellation and timeout

Rhino's instruction observer checks the request cancellation signal, thread
interruption, and a two-second monotonic deadline. Evaluation runs on a virtual
worker thread; no Minecraft-owned thread waits for it.

### Result normalization

The normalizer recursively converts Rhino values into Gson primitives, arrays,
and insertion-ordered objects. Functions, symbols, promises, host wrappers,
cycles, excessive nesting, and non-finite numbers fail explicitly.

Canonical JSON remains the internal source for UI cards, evidence validation,
traces, and subsequent scripts.

## Minecraft data graph

`MinecraftAgentDataGraph` projects `ToolInvocationContext` into stable roots:

```text
mc
├── capabilities
├── evidence
├── caller
├── player
│   └── inventory
├── items
├── blocks
├── fluids
├── effects
├── enchantments
├── recipes
├── game
│   ├── mods
│   ├── options
│   ├── packs
│   ├── diagnostics
│   └── position
└── extensions
```

Registry rows use codec-derived property/component trees already captured by
OpenAllay. The runtime does not whitelist fields such as `nutrition`, `damage`,
or `duration`; mod-added nested fields become discoverable through normal
object keys.

Data graph assembly preserves stable IDs, source, authority, completeness,
capture time, provenance, game version, and loader. Missing categories are
listed in `mc.capabilities`, never fabricated as authoritative empty data.

## Request workspace

`AgentResultWorkspaceRegistry` owns one workspace per active correlation ID.
It can store canonical results, reopen an opaque handle inside a later script,
inspect shape/cardinality, and close the complete request scope.

It exposes no path semantics, persistence, arbitrary reads, or cross-request
sharing. `GameGuideAgent` closes the scope in every terminal path. Composite
and remote executors propagate scope close to the owning endpoint.

`JavascriptResultPresenter` renders direct small values and summarizes larger
ones with schema, cardinality, preview, omitted count, evidence, and a
continuation handle. Omission is explicit and refinable, never silent factual
truncation.

## Extensions

`AgentDataAdapter` is the integration seam. It runs during Minecraft-owned
capture, converts public API values into detached evidence-bearing records, and
mounts them under `mc.extensions.<namespace>`. Adapter failures are isolated.

`AgentJavascriptModule` may contribute pure helper functions over detached
values. A trusted extension can use reflection inside its own optional capture
adapter when necessary, but ordinary model JavaScript never receives reflection
or a general Java bridge.

## Skills

Bundled Skills teach:

- inspecting the data graph before guessing fields;
- array transforms, optional chaining, grouping, and joins;
- preserving evidence;
- reopening large workspace results;
- recipe graph traversal;
- component/effect analysis;
- stopping after a conclusive aggregate.

The broad runtime Skill teaches technique. Specific Skills cover production
paths and modded-content comparison. A simple count or lookup remains one
direct script and does not require a Skill.

Bundled Skills are immutable. Managed local Skills use the existing strict
Agent Skills subset, validate a complete package, publish atomically, and affect
future requests only.

## Migration

1. Keep capture records, recipe providers, knowledge sources, GuideService,
   history, bridge chronology, and rich UI.
2. Add Rhino, workspace, data graph, result presenter, and runtime Tool.
3. Re-express deterministic actions such as craftability as helper modules.
4. Stop advertising legacy domain retrieval Tools.
5. Retain compatibility code for trace replay while it remains needed.
6. Rewrite capability settings and bundled Skills around the new surface.

## Acceptance

Deterministic fixtures must demonstrate:

1. one-call highest sword damage;
2. poison effect discovery across nested components plus recipe traversal;
3. minimum-material container recipe through filtering and reduction;
4. mod-added arbitrary field discovery;
5. large-result workspace continuation;
6. sandbox escape denial, timeout, cancellation, and cleanup;
7. atomic managed Skill lifecycle;
8. Fabric and NeoForge packaged runtime availability.

Live-provider testing remains explicit and opt-in. Credentials come only from
the environment and retained evidence is redacted.

