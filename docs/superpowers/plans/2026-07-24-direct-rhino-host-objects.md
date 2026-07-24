# Direct Rhino Host Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the request/workspace Gson serialization path with lazy read-only Rhino bindings over the original detached Java object graph.

**Architecture:** One request-scoped `MinecraftAgentHostGraph` retains detached Java records and root suppliers. Every Rhino execution creates one `RhinoHostAdapter` with identity-cached `Scriptable` wrappers for records, lists, maps, and Gson leaves; only the explicit script return value is normalized to canonical JSON.

**Tech Stack:** Java 25, KubeJS-Mods Rhino 2101.2.8-build.91, Gson 2.14, JUnit 5, Fabric 26.2, NeoForge 26.2.

---

## File map

- Create `common/src/main/java/dev/openallay/script/host/RhinoHostAdapter.java`: accepted Java value dispatcher and execution identity cache.
- Create `common/src/main/java/dev/openallay/script/host/HostObjectView.java`: read-only record/map/JsonObject properties.
- Create `common/src/main/java/dev/openallay/script/host/HostListView.java`: read-only Array-like List/Set/JsonArray view.
- Create `common/src/main/java/dev/openallay/script/host/HostRecordSchema.java`: cached record component accessors.
- Create `common/src/main/java/dev/openallay/script/host/HostAccessException.java`: stable host binding failures.
- Replace `MinecraftAgentDataProjector` with `MinecraftAgentHostGraph` and a factory that captures request-stable roots once.
- Modify `RhinoJavascriptRuntime`: bind direct host values and remove request/workspace JSON source literals.
- Modify `RunJavascriptTool`: cache host graphs, select roots before binding, and pass direct workspace values.
- Modify `AgentResultWorkspace`: return retained canonical `JsonElement` references to the direct adapter without source serialization.
- Modify `JavascriptDataModule` and `JavascriptDataModuleRegistry`: accept detached Java values directly.
- Add host/runtime/tool/extension tests and update the three analytical fixtures.

### Task 1: Direct host adapter contracts

**Files:**
- Create: `common/src/test/java/dev/openallay/script/host/RhinoHostAdapterTest.java`
- Create: `common/src/main/java/dev/openallay/script/host/HostAccessException.java`
- Create: `common/src/main/java/dev/openallay/script/host/HostRecordSchema.java`
- Create: `common/src/main/java/dev/openallay/script/host/HostObjectView.java`
- Create: `common/src/main/java/dev/openallay/script/host/HostListView.java`
- Create: `common/src/main/java/dev/openallay/script/host/RhinoHostAdapter.java`

- [ ] **Step 1: Write failing scalar, record, collection, map, Optional, enum, temporal, and JsonElement tests**

Use a nested fixture record:

```java
record Fixture(String id, List<Integer> values, Map<String, Object> properties) {}
```

Assert JavaScript can read `fixture.id`, call `fixture.values.filter(...)`,
index maps by String keys, enumerate keys, and read arbitrary nested Gson
properties without pre-conversion.

- [ ] **Step 2: Write failing immutability and host-denial tests**

Assert assignments, deletes, `push`, and direct `sort` fail
`javascript_host_read_only`. Assert `getClass`, record accessor method calls,
constructors, Java packages, reflection, and an unsupported plain Java object
fail or are absent.

- [ ] **Step 3: Implement accepted-value dispatch**

`RhinoHostAdapter.adapt(Object)` must:

```java
return switch (value) {
    case null -> null;
    case Optional<?> optional -> optional.map(this::adapt).orElse(Undefined.INSTANCE);
    case JsonElement json -> adaptJson(json);
    case String string -> string;
    case Number number -> number;
    case Boolean bool -> bool;
    case Character character -> character.toString();
    case Enum<?> enumeration -> enumeration.name();
    case UUID uuid -> uuid.toString();
    case TemporalAccessor temporal -> temporal.toString();
    case List<?> list -> cached(list, () -> new HostListView(...));
    case Set<?> set -> cached(set, () -> new HostListView(List.copyOf(set), ...));
    case Map<?, ?> map -> cached(map, () -> new HostObjectView(...));
    default when value.getClass().isRecord() ->
        cached(value, () -> new HostObjectView(value, HostRecordSchema.of(...), ...));
    default -> throw HostAccessException.unsupported(value.getClass());
};
```

- [ ] **Step 4: Implement lazy read-only Scriptable views**

Object views enumerate only component/key names. List views use
`ScriptableObject.getArrayPrototype(scope, context)`, return a numeric
`length`, and adapt indices lazily. Every mutation method throws the stable
read-only exception.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.script.host.*'
```

Expected: all host adapter tests pass.

### Task 2: Request-scoped Java host graph

**Files:**
- Create: `common/src/main/java/dev/openallay/script/data/MinecraftAgentHostGraph.java`
- Create: `common/src/test/java/dev/openallay/script/data/MinecraftAgentHostGraphTest.java`
- Delete: `common/src/main/java/dev/openallay/script/data/MinecraftAgentDataProjector.java`
- Update its existing tests/imports.

- [ ] **Step 1: Write lazy-root tests**

Use counting knowledge/module suppliers. Prove:

```java
graph.select(Set.of("items"))
```

contains direct `RegistryEntrySnapshot` references, does not resolve recipes or
game roots, and captures knowledge/extensions at most once when requested.

- [ ] **Step 2: Implement root descriptors**

Build immutable root suppliers for caller, metrics, capturedAt, player,
registry metadata, kind-grouped registry lists, recipe metadata/list, game,
knowledge, extensions, capabilities, and evidence. Group registry entries by
reference only; never serialize or deep-copy them.

- [ ] **Step 3: Implement selection**

`select(Collection<String>)` returns an immutable String-keyed Java map whose
values are resolved only for selected roots. An empty requested set exposes
all root descriptors lazily for schema discovery.

- [ ] **Step 4: Run focused graph tests**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.script.data.*'
```

Expected: no eager root resolution and reference identity is preserved.

### Task 3: Bind Java objects directly in Rhino

**Files:**
- Modify: `common/src/main/java/dev/openallay/script/RhinoJavascriptRuntime.java`
- Modify: `common/src/main/java/dev/openallay/script/OpenAllayRhinoContext.java`
- Modify: `common/src/test/java/dev/openallay/script/RhinoJavascriptRuntimeTest.java`

- [ ] **Step 1: Add a no-serialization regression test**

Inject a Java record containing a value whose `toString()` throws. Execute a
script that reads its record component successfully. This proves the input path
does not serialize or stringify the host object.

- [ ] **Step 2: Change the runtime signature**

Use:

```java
JavascriptExecution execute(
    String source,
    Map<String, Object> minecraftRoots,
    Map<String, JsonElement> workspaceValues,
    CancellationSignal cancellation)
```

- [ ] **Step 3: Bind direct globals**

Initialize safe standard objects, construct `RhinoHostAdapter`, and define
`mc`/`workspace` with `READONLY | PERMANENT | DONTENUM` where appropriate.
`workspace.open` is an OpenAllay `BaseFunction` that validates the exact
selected handle and returns `adapter.adapt(value)`.

- [ ] **Step 4: Remove data literals**

`buildProgram` may contain only the helpers and model-authored function body.
It must contain neither request/workspace data nor `JSON.parse`.

- [ ] **Step 5: Preserve deadline and normalization**

Continue to normalize only the return value with `RhinoJsonNormalizer`.
Translate `HostAccessException` into its stable `javascript_host_*` code.

- [ ] **Step 6: Run runtime tests**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.script.RhinoJavascriptRuntimeTest' --tests 'dev.openallay.script.host.*'
```

Expected: transforms, timeout, cancellation, denial, direct access, and
read-only failures pass.

### Task 4: Tool, workspace, and extension wiring

**Files:**
- Modify: `common/src/main/java/dev/openallay/tool/builtin/RunJavascriptTool.java`
- Modify: `common/src/main/java/dev/openallay/script/workspace/AgentResultWorkspace.java`
- Modify: `common/src/main/java/dev/openallay/script/extension/JavascriptDataModule.java`
- Modify: `common/src/main/java/dev/openallay/script/extension/JavascriptDataModuleRegistry.java`
- Modify: `common/src/main/java/dev/openallay/OpenAllayBootstrap.java`
- Modify focused tests under `script/workspace`, `script/extension`, and `tool/builtin`.

- [ ] **Step 1: Write request-cache and extension-record tests**

Assert one host graph per correlation ID, repeated calls reuse its captured
knowledge/extensions, direct module records are queryable, and one unsupported
module produces only a module diagnostic.

- [ ] **Step 2: Make workspace selection direct**

Return an immutable `Map<String, JsonElement>` of retained values for selected
handles. Do not concatenate, serialize, or parse selected values. Preserve
existing size and request isolation checks.

- [ ] **Step 3: Cache `MinecraftAgentHostGraph` in `RunJavascriptTool`**

Create it on first call, select roots before runtime binding, execute, normalize
the return, store canonical JSON, and build the existing bounded
model/UI presentation.

- [ ] **Step 4: Upgrade extension snapshots**

Change `Snapshot.value` to `Object`. The registry retains module Java values
without Gson conversion and validates them by adapting only when the extension
root is accessed.

- [ ] **Step 5: Run tool/extension/workspace tests**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.script.workspace.*' \
  --tests 'dev.openallay.script.extension.*' \
  --tests 'dev.openallay.tool.builtin.RunJavascriptAcceptanceTest'
```

Expected: direct reopening, module isolation, request cleanup, and all three
analytical scenarios pass.

### Task 5: Prompt, docs, and architectural proof

**Files:**
- Modify: `common/src/main/java/dev/openallay/agent/AgentSystemPrompt.java`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/**`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-24-026-direct-rhino-host-objects.md`
- Add: `common/src/test/java/dev/openallay/architecture/DirectRhinoHostArchitectureTest.java`

- [ ] **Step 1: Add architecture assertions**

Assert production runtime source has no `minecraftData.toString()`,
`workspaceValues.toString()`, `JSON.parse(%s)`, or Gson dependency. Assert the
host adapter never subclasses/constructs `NativeJavaObject`.

- [ ] **Step 2: Update prompt and Skill terminology**

Teach that `mc` roots are lazy Java-backed immutable views, that array
transforms are normal, and that scripts must derive a new array before sorting
or mutating.

- [ ] **Step 3: Update development and decision status**

Document the direct host ABI for extension authors. Mark SKMB-026 verified only
after focused and full gates pass.

- [ ] **Step 4: Run prompt, Skill, and architecture tests**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.agent.AgentSystemPromptTest' \
  --tests 'dev.openallay.skill.BundledSkillsTest' \
  --tests 'dev.openallay.architecture.DirectRhinoHostArchitectureTest'
```

Expected: direct-host guidance and no-serialization architecture checks pass.

### Task 6: Full verification and publication

**Files:**
- Update: `docs/verification/rhino-agent-runtime/live-javascript-agent.md`

- [ ] **Step 1: Run all common tests and both loader builds**

Run:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Expected: all tasks succeed and Rhino remains packaged for both loaders.

- [ ] **Step 2: Run opt-in live Agent acceptance**

Use the authorized provider through `scripts/live-model-smoke.sh javascript-agent`.
Expected: sword and container tasks finish in at most two JavaScript analysis
calls each without per-row Tool loops. Retain no credential.

- [ ] **Step 3: Audit the exact diff**

Run:

```bash
git diff --check
git status --short
rg -n 'JSON\\.parse|minecraftData\\.toString|workspaceValues\\.toString' common/src/main/java/dev/openallay/script
```

Expected: clean whitespace, only intended files, and no request input
serialization path.

- [ ] **Step 4: Commit and push**

Commit:

```bash
git commit -m "feat: bind Rhino directly to Java snapshots"
```

Push `feat/js-agent-runtime` without rewriting history.
