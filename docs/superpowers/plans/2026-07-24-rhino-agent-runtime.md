# Rhino Agent Runtime Implementation Plan

> **For Codex:** Execute incrementally with focused tests. Do not restore the
> rejected Resource VFS surface.

**Goal:** Replace advertised domain retrieval Tools with a KubeJS-Rhino-based
JavaScript analysis runtime, request-scoped large-result workspace, managed
Skill writes, and three end-to-end analytical workflows.

**Architecture:** Minecraft-owned threads capture immutable data as in 0.1.0.
Common code projects the snapshot into a stable JavaScript data graph, executes
model-authored code in a fresh locked-down Rhino Context, normalizes the result
to canonical Gson JSON, stores it in a request workspace, and sends only an
efficient evidence-preserving projection to the model.

**Tech stack:** Java 25, KubeJS-Mods Rhino 2101.2.8-build.91, Gson, JUnit 5,
Fabric 26.2, NeoForge 26.2.

## Task 1: Pin and package Rhino

**Files:** `gradle.properties`, root/common/Fabric/NeoForge Gradle files.

1. Add the official Latvian releases repository restricted to
   `dev.latvian.mods`.
2. Pin `rhino_version=2101.2.8-build.91`.
3. Compile common code against Rhino.
4. Include it in Fabric and jarJar it in NeoForge.
5. Verify both artifacts contain a usable runtime without a separate install.

## Task 2: Build the isolated Rhino kernel

**Files:** new `dev.openallay.script` runtime classes and focused tests.

1. Test `filter`, `map`, `reduce`, sort, grouping, and object/array output.
2. Test denial of packages, adapters, class loading, reflection, filesystem,
   network, processes, and host-object leakage.
3. Test cancellation, deadline, syntax error, and cyclic output.
4. Implement dedicated ContextFactory/Context, safe standard objects,
   interpreted mode, instruction observation, and explicit bindings.
5. Normalize output to Gson and discard every scope.

## Task 3: Project the Minecraft data graph

**Files:** new `script/data` projector, adapter registry, and tests.

1. Project caller, player/inventory, registries, recipes, observable game state,
   metrics, capabilities, and evidence.
2. Preserve absent/partial distinctions and arbitrary nested component fields.
3. Add extension adapter registration and isolated failure diagnostics.
4. Prove a new nested mod field is queryable without a core schema change.

## Task 4: Add the request result workspace

**Files:** new `script/workspace` classes; Agent executor and lifecycle changes.

1. Store canonical results under opaque request-local handles.
2. Reopen handles only inside the matching request.
3. Present scalar, record, schema/cardinality, preview, omitted count, evidence,
   and continuation guidance.
4. Close workspaces on completion, failure, cancellation, disconnect, and
   shutdown.
5. Prove cross-request and post-close access fails.

## Task 5: Register `run_javascript`

**Files:** new built-in Tool, bootstrap/capability/localization changes.

1. Define a minimal source input schema.
2. Project captured data and current workspace.
3. Execute asynchronously; retain canonical output and return the projection.
4. Register the Tool for client and server-capable common paths.
5. Stop advertising legacy domain retrieval Tools; retain narrow deterministic
   operations as JS helpers.

## Task 6: Rewrite prompt and bundled Skills

**Files:** `AgentSystemPrompt`, bundled Skill packages, Skill tests.

1. Teach runtime discovery, one-call analysis, workspace continuation, and
   evidence handling.
2. Remove old domain Tool names from bundled instructions.
3. Add progressive examples for ranking, grouping, nested components, joins,
   and recipe graphs.
4. Keep simple one-script requests Skill-optional.

## Task 7: Implement managed Skill writes

**Files:** `ManagedSkillStore`, `ManageSkillTool`, wiring, and tests.

1. Accept exact Skill names and package-relative files only.
2. Stage and validate complete packages with the existing parser.
3. Atomically publish create/update and delete one exact managed package.
4. Roll back on validation, confinement, dependency, I/O, or generation
   failures.
5. Apply catalog changes only to future requests.

## Task 8: Preserve UI and diagnostics

1. Normal mode shows intent, execution state, useful result summary, and
   continuation state.
2. Debug mode adds source length, elapsed time, schema/cardinality, handle
   ownership, and redacted failures.
3. Never show full large canonical JSON by default.
4. Preserve invocation identity and chronology.

## Task 9: Analytical E2E fixtures

1. Highest-damage sword in one execution.
2. Strongest poison-causing obtainable item plus production path.
3. Craftable container recipe requiring the fewest materials.
4. Assert no legacy retrieval Tool is advertised or called.
5. Assert large intermediates remain workspace-resident.

## Task 10: Documentation and verification

1. Update player READMEs without implementation jargon.
2. Document runtime, adapters, managed Skills, and commands in
   `docs/development.md`.
3. Complete SKMB states, transitions, invariants, and failures.
4. Run focused tests during implementation.
5. Run `./gradlew clean :common:test :fabric:build :neoforge:build`.
6. Run live-provider scenarios only with environment credentials.
7. Audit artifacts, diff, credentials, loader parity, and remaining risks.

