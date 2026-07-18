# Phase 4D Context Reduction and Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Assemble every model request from a budgeted, provider-neutral context projection that preserves current work and tool structure, reduces old tool results, creates reusable structured summary checkpoints when needed, and fails closed without changing durable history.

**Architecture:** Common code owns immutable budgets, estimates, structural reductions, checkpoint hashes, summary generation, and projections. `GameGuideAgent` invokes this pipeline before the first model turn while keeping the current request boundary protected through all tool continuations. `AgentSessionStore` owns complete in-memory history plus checkpoints; GuideService persists only privacy-safe checkpoint projections and hydrates matching normal-mode message history. Sessions and valid derived checkpoints remain provider/model-neutral; each client or server request derives a fresh budget from its selected `ModelConfig`.

**Tech Stack:** Java 25 records and sealed content, Gson strict JSON, SHA-256 source hashes, existing `ModelClient`/scheduler/cancellation contracts, SQLite schema migration, JUnit 5 deterministic fake models.

---

## Accepted Boundaries

- This is an internal work package in the single Phase 4, not a separate product phase.
- SKMB-2026-07-18-005 owns product semantics; SKMB-2026-07-18-008 records reviewable execution defaults.
- Complete player-visible history remains durable and unchanged. Normal mode does not add full normalized tool inputs/results or reasoning to SQLite.
- Summary checkpoints are derived memory, never evidence; current game facts still require current evidence or a fresh tool call.
- No message-count, history-length, checkpoint-count, or database-size cap is introduced.

### Task 1: Define Budget, Estimator, and Structural Units

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextBudget.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextTokenEstimator.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/Utf8ContextTokenEstimator.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextStructure.java`
- Test: `common/src/test/java/dev/tomewisp/agent/context/Utf8ContextTokenEstimatorTest.java`
- Test: `common/src/test/java/dev/tomewisp/agent/context/ContextStructureTest.java`

- [x] Write red tests for system/tool/JSON accounting, Unicode determinism, invalid budgets, assistant tool-use plus user tool-result grouping, orphan/duplicate result rejection, and reasoning exclusion.
- [x] Run `./gradlew :common:test --tests 'dev.tomewisp.agent.context.*'` and confirm compilation failure.
- [x] Implement the immutable budget, conservative UTF-8 estimator, and structural unit validator without provider imports.
- [x] Run the focused tests and commit `feat: define context budget and structure`.

The red run failed with 19 missing-symbol compilation errors before the context
domain existed. The green focused run passed all seven estimator/structure
tests in four seconds. Reasoning remains countable in a live primary request but
is explicitly stripped by the summary-safe projection.

### Task 2: Reduce Old Tool Results Without Losing Evidence

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/context/ReducedToolResult.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ToolResultContextReducer.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextProjection.java`
- Test: `common/src/test/java/dev/tomewisp/agent/context/ToolResultContextReducerTest.java`

- [x] Write red tests using large recipe-search, inventory, document, explicit failure, unknown-tool, and malformed-result payloads.
- [x] Prove old results retain status/output type, stable reference IDs, evidence authority/completeness/capture/source/provenance, and failure code while dropping bulk lists.
- [x] Prove messages at or after `protectedFromIndex` and their JSON deep copies are unchanged.
- [x] Implement deterministic key allowlisting and structural projection; malformed old results remain explicit reduced failures rather than disappearing.
- [x] Run focused tests and commit `feat: reduce historical tool context`.

The red run failed with five missing-symbol errors. The green context package
run passed eleven tests in five seconds. The bulk fixture shrinks by more than
80% while retaining both viewer references, its evidence record, the stable
Patchouli reference, and craftability conclusion fields.

### Task 3: Generate and Validate Summary Checkpoints

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextCheckpoint.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextCheckpointCodec.java`
- Create: `common/src/main/java/dev/tomewisp/agent/context/ContextCompactor.java`
- Test: `common/src/test/java/dev/tomewisp/agent/context/ContextCompactorTest.java`

- [x] Write red fake-model tests for no-op projection, deterministic-only projection, successful same-model summary, malformed summary, transport failure fallback, still-too-large failure, source hash stability, stale checkpoint rejection, and cancellation before/while summary.
- [x] Implement a versioned JSON-only summary prompt containing goals, preferences, completed topics, current tasks, decisions, unresolved questions, and evidence references; omit reasoning content before serialization.
- [x] Budget summary input, summarize only a structural prefix, prefix inserted memory as derived/non-evidence, validate exact output fields, and retain structured failed checkpoints.
- [x] Ensure every primary or summary request uses the original scheduling key and cancellation signal.
- [x] Run focused tests and commit `feat: compact model context with checkpoints`.

The context package run passed sixteen tests in four seconds. Deterministic
fixtures cover original/reduced/summarized projections, strict checkpoint
round trips, same-key summary dispatch, source-hash invalidation, malformed and
provider-failed summaries, and pre-dispatch cancellation. Cancellation during a
pending summary is additionally exercised at the Agent integration boundary in
Task 4, where late primary dispatch can be observed.

### Task 4: Integrate Compaction Into the Agent Loop

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/agent/AgentState.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/session/AgentSessionStore.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestStatus.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Modify: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodec.java`
- Modify: `common/src/test/java/dev/tomewisp/agent/GameGuideAgentTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`
- Modify: `common/src/test/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodecTest.java`

- [x] Write red tests proving `PREPARING -> COMPACTING -> MODEL_WAIT`, checkpoint event correlation, current tool continuation preservation, no primary dispatch after terminal compaction failure, failure leaves old history intact, and cancellation suppresses late summary/primary output.
- [x] Add `COMPACTING` state/status and a strict privacy-safe checkpoint event to local and server codecs.
- [x] Integrate one compactor call before initial dispatch; carry the protected boundary through later tool turns and retain original complete history on successful completion.
- [x] Store checkpoints by session without allowing an old lease or late request to overwrite a replacement.
- [x] Run Agent/Guide/bridge tests and commit `feat: compact agent request context`.

The focused Agent/Guide/bridge suite passed in five seconds. Agent integration
proves the summarized projection is sent while six complete original/current
messages are committed, malformed summary failure leaves the prior four-message
history intact, cancellation during a pending summary emits no primary request,
and the strict server event codec round-trips the checkpoint without Gson
reflection over `Instant`.

### Task 5: Configure the Selected Model Budget on Both Topologies

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelConfig.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelConfigLoader.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentHistoryMessage.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentRequestChunkPayload.java`
- Create: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentRequestChunker.java`
- Modify: Fabric and NeoForge client/server bridge adapters
- Modify: `common/src/main/java/dev/tomewisp/server/ServerGuideRuntime.java`
- Modify: `common/src/test/java/dev/tomewisp/model/config/ModelConfigLoaderTest.java`
- Modify direct `ModelConfig` fixtures under `common/src/test/java/dev/tomewisp/model/`
- Modify: `docs/development.md`

- [x] Add red config tests for JSON/environment precedence, required per-model context window, double-output reserve validation, and redacted diagnostics.
- [x] Add `contextWindowTokens` / `TOMEWISP_CONTEXT_WINDOW_TOKENS`, pass the same derived `ContextBudget` into client and server agents, and update the documented sample.
- [x] Run model config, Anthropic, OpenAI, client runtime, and server runtime tests.
- [x] Commit `feat: configure model context budgets`.

The focused configuration, Anthropic, OpenAI, client, and server tests passed in
five seconds. Production runtime creation now gives the compactor the exact
same scheduled client and selected model identifier as primary dispatch; direct
test constructors retain an explicit no-compaction path for small isolated
fixtures. A later designer review removed the provider-independent 128,000
default: the selected model now requires an explicit window unless a trusted
metadata adapter resolves it with provenance.

### Task 6: Persist and Recover Privacy-Safe Checkpoints

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSessionSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPartition.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryRepository.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideLocalEndpoint.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryCodecTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideServiceHistoryTest.java`

- [x] Write red migration/round-trip tests from schema v1 to v2, exact checkpoint fields, normal-mode privacy exclusions, partition isolation, failed checkpoint retention, and stale-hash non-reuse.
- [x] Add a transactional v1-to-v2 migration and `compaction_checkpoints` table; never delete the original messages/timeline rows during migration.
- [x] Project checkpoint events into the owning session, persist asynchronously in event order, and hydrate only matching-partition message/checkpoint context after durable load.
- [x] Reconstruct normal-mode old context from user/completed-assistant messages plus validated checkpoint summaries; never restore capabilities, live evidence, inventory, recipe generations, or active requests.
- [x] Run history/recovery/architecture tests and commit `feat: persist context checkpoints`.

Schema v2 migration and round-trip fixtures preserve the original message and
timeline row counts, isolate successful and failed checkpoints by partition,
and prove normal checkpoint payloads cannot represent reasoning, authorization,
or normalized tool data. Client-local recovery hydrates the shared session
store; server-model recovery carries only visible user/completed-assistant
messages through strict protocol v4, split into hash-checked 24 KiB transport
chunks for both loaders. The session store atomically installs restored history
with the winning lease, so a concurrent same-session request still terminates
as `agent_busy`. Focused history, recovery, Agent, context, bridge, server, and
architecture tests plus Fabric/NeoForge compilation passed on 2026-07-18.

Designer clarification during this task made model neutrality explicit: a
session is not bound to one model, and a valid derived checkpoint may cross
provider/model boundaries. Its generating model ID is provenance only; reuse
still requires exact source hash and supported summary versions, and every new
request is re-estimated against the selected model's own budget.

### Task 7: Verify Phase 4D and Update Status

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-18-008-context-compaction-execution.md`
- Modify: this plan

- [ ] Run `./gradlew :common:test --tests 'dev.tomewisp.agent.context.*' --tests 'dev.tomewisp.agent.*' --tests 'dev.tomewisp.guide.*' --tests 'dev.tomewisp.bridge.protocol.*'`.
- [ ] Run `./gradlew clean :common:test :fabric:build :neoforge:build`.
- [ ] Run `git diff --check`, shell/Python/JSON syntax checks, production-JAR credential scans, and verify no reasoning/full normalized history was added to SQLite fixtures.
- [ ] Record test counts, artifact hashes, warnings, and commit references in this plan and SKMB-008.
- [ ] Commit `docs: verify context compaction`.

## Completion Boundary

Phase 4D is complete only when deterministic reduction, same-topology summary,
checkpoint persistence/recovery, both loaders, and failure/cancellation races
are proven. Semantic rich messages, settings/developer diagnostics, history
paging, and final consolidated graphical smoke remain active Phase 4 work.
