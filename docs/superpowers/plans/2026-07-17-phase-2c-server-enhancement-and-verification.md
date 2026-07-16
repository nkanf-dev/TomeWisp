# Phase 2C Server Enhancement and Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the optional authenticated read-tool bridge, optional server-hosted Agent, lifecycle cancellation, live-provider acceptance, and final dual-loader/CI proof for all Phase 2 requirements.

**Architecture:** A versioned common wire model transports capability descriptors, remote tool calls/results, server Agent requests, and progress/final events. Loader modules implement their native payload APIs, while common coordinators own correlation, identity, permissions, fragmentation, cancellation, and state. Client-only operation remains untouched when no handshake is received.

**Tech Stack:** Java 25, Minecraft custom payload codecs, Fabric networking API, NeoForge payload handlers, JUnit, JDK HttpClient, Gradle/GitHub Actions.

---

### Task 1: Versioned bridge wire model and codecs

**Files:**
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/BridgeProtocol.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/CapabilityPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/RemoteToolCallPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/RemoteToolResultChunkPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/RemoteCancelPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentRequestPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentEventPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/BridgeJsonCodec.java`
- Test: `common/src/test/java/dev/tomewisp/bridge/protocol/BridgeJsonCodecTest.java`

- [ ] **Step 1: Write round-trip, malformed, and complete-fragment tests**

Assert Unicode JSON, protocol versions, UUID correlation IDs, hashes, out-of-order
chunks, duplicate chunks, missing chunks, and full reassembly without logical
truncation.

- [ ] **Step 2: Run and verify missing protocol**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.bridge.protocol.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement pure records and canonical codecs**

Every decoder checks an exact schema and protocol version before constructing a
record. Chunk hashes use SHA-256 and payload content contains no player UUID for
identity authorization.

- [ ] **Step 4: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.bridge.protocol.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/bridge common/src/test/java/dev/tomewisp/bridge
git commit -m "feat: define server enhancement protocol"
```

### Task 2: Common remote tool coordinators

**Files:**
- Create: `common/src/main/java/dev/tomewisp/bridge/client/RemoteToolExecutor.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/client/RemoteCapabilityStore.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/server/RemoteToolServer.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/server/ExportedToolPolicy.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/CorrelationRegistry.java`
- Test: `common/src/test/java/dev/tomewisp/bridge/RemoteToolBridgeTest.java`

- [ ] **Step 1: Write identity, permission, race, disconnect, and late-result tests**

Use two fake players and assert one cannot complete/cancel/read the other's
correlation. Assert only exported read-only tools run, duplicate correlations
fail, disconnect cancels, and late chunks are ignored after cancellation.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.bridge.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement coordinators using connection-derived identity**

`RemoteToolServer.handle(sender, payload)` accepts the sender object from the
loader adapter, looks up the tool policy, schedules context capture on the
server thread, invokes the tool, and responds only through that sender's
connection.

- [ ] **Step 4: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.bridge.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/bridge common/src/test/java/dev/tomewisp/bridge
git commit -m "feat: authorize remote read tools"
```

### Task 3: Fabric networking and lifecycle adapter

**Files:**
- Modify: `fabric/build.gradle`
- Create: `fabric/src/main/java/dev/tomewisp/fabric/network/FabricBridgePayloads.java`
- Create: `fabric/src/main/java/dev/tomewisp/fabric/network/FabricClientBridge.java`
- Create: `fabric/src/main/java/dev/tomewisp/fabric/network/FabricServerBridge.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabric.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `fabric/src/main/resources/fabric.mod.json`

- [ ] **Step 1: Add exact Fabric networking modules from the current Fabric API BOM**

Add the 26.2-compatible networking and lifecycle modules and declare them in
`fabric.mod.json`.

- [ ] **Step 2: Register codecs, receivers, login handshake, and disconnect cancellation**

All server receivers execute through Fabric's provided server context. Client
receivers schedule through the Minecraft client context. A server without model
config advertises remote tools but not `serverModel=true`.

- [ ] **Step 3: Compile and inspect Fabric payload registrations**

Run: `./gradlew-curl :fabric:compileJava :fabric:build --max-workers=1`

Expected: PASS; JAR includes all bridge classes and no model config.

- [ ] **Step 4: Commit**

```bash
git add fabric
git commit -m "feat: connect Fabric server enhancements"
```

### Task 4: NeoForge networking and lifecycle adapter

**Files:**
- Create: `neoforge/src/main/java/dev/tomewisp/neoforge/network/NeoForgeBridgePayloads.java`
- Create: `neoforge/src/main/java/dev/tomewisp/neoforge/network/NeoForgeClientBridge.java`
- Create: `neoforge/src/main/java/dev/tomewisp/neoforge/network/NeoForgeServerBridge.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForge.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`

- [ ] **Step 1: Register payload handlers on the mod event bus**

Use NeoForge's 26.2 payload registrar with explicit protocol version. Handlers
derive `ServerPlayer` from the payload context and enqueue work on the owning
thread.

- [ ] **Step 2: Wire login/logout and capability updates**

Login sends current capabilities. Config/resource reload sends a replacement
snapshot. Logout cancels common correlations and server Agent sessions.

- [ ] **Step 3: Compile and build NeoForge**

Run: `./gradlew-curl :neoforge:compileJava :neoforge:build --max-workers=1`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add neoforge
git commit -m "feat: connect NeoForge server enhancements"
```

### Task 5: Optional server-hosted Agent

**Files:**
- Create: `common/src/main/java/dev/tomewisp/server/ServerGuideRuntime.java`
- Create: `common/src/main/java/dev/tomewisp/server/ServerAgentService.java`
- Create: `common/src/main/java/dev/tomewisp/server/ServerAgentQueue.java`
- Create: `common/src/main/java/dev/tomewisp/server/ServerGuideEvents.java`
- Modify: `common/src/main/java/dev/tomewisp/TomeWispRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/TomeWispBootstrap.java`
- Test: `common/src/test/java/dev/tomewisp/server/ServerAgentServiceTest.java`

- [ ] **Step 1: Write capability, identity, fair queue, and credential-isolation tests**

Assert the server advertises model capability only with a valid server config;
client packets contain no key; requests use sender identity; per-player FIFO is
preserved; players rotate fairly; configured concurrency is honored; queued and
running work cancels on disconnect; and failure releases a slot.

- [ ] **Step 2: Implement server Agent using the same GameGuideAgent**

The service captures server context on the server thread, runs the model on a
virtual thread, emits versioned events to the sender, and cancels on disconnect.
`ServerAgentQueue` captures context/history only when a fair execution slot is
granted. It does not share client conversation history or credentials and does
not impose a queue-count limit.

- [ ] **Step 3: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.server.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/server common/src/main/java/dev/tomewisp/TomeWispRuntime.java common/src/main/java/dev/tomewisp/TomeWispBootstrap.java common/src/test/java/dev/tomewisp/server
git commit -m "feat: host optional server guide agent"
```

### Task 6: Development diagnostics and dynamic trace persistence

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/trace/LiveTraceStore.java`
- Create: `common/src/main/java/dev/tomewisp/agent/trace/LiveTraceJson.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/FabricDevelopmentCommands.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgeDevelopmentCommands.java`
- Test: `common/src/test/java/dev/tomewisp/agent/trace/LiveTraceStoreTest.java`

- [ ] **Step 1: Write redaction, canonical JSON, and disabled-persistence tests**

Assert full questions/tool results survive, authorization headers and key values
cannot be represented, persistence writes only when configured, and no automatic
retention deletion occurs.

- [ ] **Step 2: Implement in-memory latest traces and optional filesystem sink**

Use atomic file replacement in the configured trace directory. Filenames are
request UUIDs; paths never come from the model. Add operator commands for model
probe, trace list/show, Skill validation, source diagnostics, and bridge status.

- [ ] **Step 3: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.agent.trace.*' :fabric:compileJava :neoforge:compileJava --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/agent/trace common/src/test/java/dev/tomewisp/agent/trace fabric/src/main/java/dev/tomewisp/fabric/FabricDevelopmentCommands.java neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgeDevelopmentCommands.java
git commit -m "feat: inspect redacted live agent traces"
```

### Task 7: Opt-in live provider acceptance harness

**Files:**
- Create: `common/src/test/java/dev/tomewisp/model/live/LiveModelAcceptanceTest.java`
- Create: `scripts/live-model-smoke.sh`
- Modify: `.gitignore`
- Modify: `docs/development.md`

- [ ] **Step 1: Implement an environment-gated live test**

The test skips unless `TOMEWISP_LIVE_MODEL=true` and required model environment
variables exist. It registers a deterministic `tomewisp:test_fact` tool, asks a
Chinese question, requires one real tool use and continuation, requires final
Chinese text containing the tool fact, tests streaming, and scans the trace for
the key.

- [ ] **Step 2: Run mock CI path**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.model.*' --max-workers=1`

Expected: live test skipped; all mock tests pass.

- [ ] **Step 3: Run supplied gateway acceptance with environment-only secret**

Run through an interactive shell that reads the key silently, exports it only
for the Gradle child, and invokes the live test. Never place the key in command
arguments or files.

Expected: model listing/plain/tool/result/final/stream/redaction checks PASS.

- [ ] **Step 4: Commit harness and docs**

```bash
git add common/src/test/java/dev/tomewisp/model/live scripts/live-model-smoke.sh .gitignore docs/development.md
git commit -m "test: verify live model tool calling"
```

### Task 8: Completion audit, loaders, CI, and merge

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `.github/workflows/build.yml` only if mock tests require an explicit task
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-17-001-client-first-agent-runtime.md`

- [ ] **Step 1: Run the full clean verification**

Run: `TOMEWISP_CURL_PROXY=socks5h://127.0.0.1:7890 ./gradlew-curl clean :common:test :fabric:build :neoforge:build --max-workers=1`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run both dedicated servers with no GUI and no model config**

Fabric: `./gradlew-curl :fabric:runServer --args nogui --max-workers=1`

NeoForge: `./gradlew-curl :neoforge:runServer --max-workers=1`

On both, run Phase 1 replay commands, bridge diagnostics, and `stop`. Expected:
normal startup, existing replays pass, server model capability is false.

- [ ] **Step 3: Verify client registration without opening a GUI**

Run loader-provided client compile/data-generation or a dedicated entrypoint
registration test harness. Inspect both JAR manifests for client entrypoints and
the same `/guide` command branches.

- [ ] **Step 4: Audit every completion-gate requirement**

Create a requirement/evidence table from the Phase 2 spec. Inspect tests, built
JARs, live trace, server logs, resource fixtures, bridge tests, and secret scans.
Any missing or indirect evidence keeps the goal open.

- [ ] **Step 5: Update SKMB commit references and documentation**

Record final implementation commits without changing accepted semantics.
Document client-only, enhanced, and server-model deployment.

- [ ] **Step 6: Commit, push, merge, and watch CI**

```bash
git diff --check
git status --short --branch
git push --set-upstream origin feat/phase-2-live-agent
```

Fast-forward `main`, push it, and watch the exact GitHub Actions run to success.
Only then remove the worktree and branches and mark the Phase 2 goal complete.
