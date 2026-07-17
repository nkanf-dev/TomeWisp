# Phase 4A Chronological Agent Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flattened per-request assistant text and trailing tool list with a strict, durable-ready chronological timeline that preserves assistant segments, exact tool invocation identity, later continuations, and terminal reconciliation across local and server Agent paths.

**Architecture:** `GuideRequestSnapshot` will own an immutable ordered list of `GuideTimelineEntry` values. The Agent emits stable model tool-call IDs on tool start and completion, the common reducer appends or updates entries without reordering, and `GuideUiView` projects the timeline directly. Compatibility accessors keep command/session behavior narrow while protocol version 3 makes the new invocation identity strict on the wire.

**Tech Stack:** Java 25 records and sealed interfaces, Gson strict codecs, JUnit 5, Gradle, Minecraft native screen projection, Fabric and NeoForge common bridge protocol.

---

## Execution review correction

Task 1 changes the primary snapshot and tool constructors before Task 4 rewrites
the reducer. To keep every intermediate commit compilable, Task 1 may add
package-private migration constructors that translate the old flattened
arguments into a timeline and assign an explicit `migration-<index>` invocation
ID only for existing in-process test fixtures. Task 4 must delete those
migration constructors after every production and test call site supplies the
real model invocation ID. The final implementation must contain no path that
correlates tools by name or invents runtime invocation identity.

This correction changes only implementation sequencing; it does not change the
approved product semantics.

## File map

- Create `common/src/main/java/dev/tomewisp/guide/GuideTimelineEntry.java`: immutable ordered assistant/tool entries and ordinal validation.
- Modify `common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java`: add stable `invocationId` while preserving deterministic display index.
- Modify `common/src/main/java/dev/tomewisp/guide/GuideRequestSnapshot.java`: store timeline and derive final assistant text/tool lists for compatibility consumers.
- Modify `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`: carry invocation IDs on tool start/completion.
- Modify `common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java`: emit model `ToolUse.id()` through the full tool lifecycle.
- Modify `common/src/main/java/dev/tomewisp/bridge/protocol/BridgeProtocol.java`: bump the strict server event protocol to version 3.
- Modify `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodec.java`: encode/decode the new strict event fields.
- Modify `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`: append assistant segments, correlate exact tools, and reconcile only the final segment.
- Modify `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`: project the stored chronological timeline.
- Modify `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`: carry ordinal identity needed by stable UI selection and later persistence.
- Modify `common/src/main/java/dev/tomewisp/guide/GuideCommandFacade.java`: observe derived tool activity without depending on flattened storage.
- Test `common/src/test/java/dev/tomewisp/agent/AgentEventTest.java`.
- Test `common/src/test/java/dev/tomewisp/agent/GameGuideAgentTest.java`.
- Test `common/src/test/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodecTest.java`.
- Test `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`.
- Test `common/src/test/java/dev/tomewisp/guide/GuideServiceTest.java`.
- Test `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`.
- Test `common/src/test/java/dev/tomewisp/guide/GuideProductE2ETest.java`.

### Task 1: Introduce the immutable timeline domain

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/GuideTimelineEntry.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestSnapshot.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`

- [x] **Step 1: Write a failing snapshot-domain test**

Add a test that constructs one assistant segment, one tool, and a later assistant
segment, then verifies order and compatibility accessors:

```java
@Test
void snapshotDerivesFinalTextAndToolsFromChronologicalTimeline() {
    UUID requestId = UUID.randomUUID();
    GuideToolActivity tool = new GuideToolActivity(
            "call-1", 0, "tomewisp:get_recipe",
            GuideToolStatus.SUCCEEDED, groundedResult(), List.of());
    GuideRequestSnapshot request = new GuideRequestSnapshot(
            requestId, "main", GuideTopology.CLIENT_LOCAL, "How?",
            List.of(
                    new GuideTimelineEntry.Assistant(0, "I will check.", false, List.of()),
                    new GuideTimelineEntry.Tool(1, tool),
                    new GuideTimelineEntry.Assistant(2, "You need nine ingots.", false, List.of())),
            GuideRequestStatus.COMPLETED,
            List.of(), ModelUsage.empty(), null, null,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(3), Instant.EPOCH.plusSeconds(3));

    assertEquals(List.of(0, 1, 2), request.timeline().stream()
            .map(GuideTimelineEntry::ordinal).toList());
    assertEquals("You need nine ingots.", request.assistantText());
    assertEquals(List.of(tool), request.tools());
}
```

- [x] **Step 2: Run the focused test and confirm the domain does not exist**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideStateReducerTest.snapshotDerivesFinalTextAndToolsFromChronologicalTimeline'
```

Expected: compilation fails because `GuideTimelineEntry` and the new
constructors do not exist.

- [x] **Step 3: Add the timeline sealed interface**

Create:

```java
package dev.tomewisp.guide;

import java.util.List;

public sealed interface GuideTimelineEntry
        permits GuideTimelineEntry.Assistant, GuideTimelineEntry.Tool {
    int ordinal();

    record Assistant(
            int ordinal,
            String text,
            boolean streaming,
            List<GuideSource> sources) implements GuideTimelineEntry {
        public Assistant {
            requireOrdinal(ordinal);
            text = text == null ? "" : text;
            sources = List.copyOf(sources);
        }
    }

    record Tool(int ordinal, GuideToolActivity activity) implements GuideTimelineEntry {
        public Tool {
            requireOrdinal(ordinal);
            java.util.Objects.requireNonNull(activity, "activity");
        }
    }

    private static void requireOrdinal(int ordinal) {
        if (ordinal < 0) throw new IllegalArgumentException("timeline ordinal must not be negative");
    }
}
```

- [x] **Step 4: Add stable invocation identity to tool activity**

Change the record header and validation to:

```java
public record GuideToolActivity(
        String invocationId,
        int index,
        String toolId,
        GuideToolStatus status,
        JsonObject normalized,
        List<GuideSource> sources) {
    public GuideToolActivity {
        if (invocationId == null || invocationId.isBlank()
                || index < 0 || toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool activity identity is invalid");
        }
        java.util.Objects.requireNonNull(status, "status");
        normalized = normalized == null ? null : normalized.deepCopy();
        sources = List.copyOf(sources);
    }
}
```

- [x] **Step 5: Replace snapshot storage with the timeline and compatibility accessors**

Use `List<GuideTimelineEntry> timeline` in the record header. Defensively copy
and validate contiguous ordinals in the compact constructor:

```java
timeline = List.copyOf(timeline);
for (int index = 0; index < timeline.size(); index++) {
    if (timeline.get(index).ordinal() != index) {
        throw new IllegalArgumentException("timeline ordinals must be contiguous");
    }
}
```

Add derived accessors:

```java
public String assistantText() {
    for (int index = timeline.size() - 1; index >= 0; index--) {
        if (timeline.get(index) instanceof GuideTimelineEntry.Assistant assistant) {
            return assistant.text();
        }
    }
    return "";
}

public List<GuideToolActivity> tools() {
    return timeline.stream()
            .filter(GuideTimelineEntry.Tool.class::isInstance)
            .map(GuideTimelineEntry.Tool.class::cast)
            .map(GuideTimelineEntry.Tool::activity)
            .toList();
}
```

Initialize `start(...)` with `List.of()`.

- [x] **Step 6: Update direct constructor call sites mechanically**

Replace old `assistantText, status, tools` constructor arguments with a timeline.
For fixtures containing text only, use:

```java
text.isBlank()
        ? List.of()
        : List.of(new GuideTimelineEntry.Assistant(0, text, terminal == null, List.of()))
```

Do not change reducer behavior yet.

- [x] **Step 7: Run the focused test**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideStateReducerTest.snapshotDerivesFinalTextAndToolsFromChronologicalTimeline'
```

Expected: PASS.

- [x] **Step 8: Commit the timeline domain**

```bash
git add common/src/main/java/dev/tomewisp/guide/GuideTimelineEntry.java \
  common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java \
  common/src/main/java/dev/tomewisp/guide/GuideRequestSnapshot.java \
  common/src/test/java/dev/tomewisp/guide
git commit -m "refactor: add chronological guide timeline"
```

### Task 2: Carry exact tool invocation IDs through Agent events

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java`
- Modify: `common/src/test/java/dev/tomewisp/agent/AgentEventTest.java`
- Modify: `common/src/test/java/dev/tomewisp/agent/GameGuideAgentTest.java`

- [x] **Step 1: Write failing event identity tests**

Add:

```java
@Test
void toolEventsDefensivelyRetainInvocationIdentity() {
    JsonObject normalized = new JsonObject();
    AgentEvent.ToolStarted started = new AgentEvent.ToolStarted(
            "model-call-7", "tomewisp:get_recipe");
    AgentEvent.ToolCompleted completed = new AgentEvent.ToolCompleted(
            "model-call-7", "tomewisp:get_recipe", false, normalized);

    assertEquals("model-call-7", started.invocationId());
    assertEquals("model-call-7", completed.invocationId());
    normalized.addProperty("mutated", true);
    assertFalse(completed.normalized().has("mutated"));
}
```

Extend the Agent loop test fixture so a `ModelContent.ToolUse` with ID
`call-recipe` produces matching started and completed events.

- [x] **Step 2: Run the Agent tests and confirm constructor failures**

```bash
./gradlew :common:test --tests 'dev.tomewisp.agent.AgentEventTest' \
  --tests 'dev.tomewisp.agent.GameGuideAgentTest'
```

Expected: compilation fails on the new event constructors.

- [x] **Step 3: Change the Agent event records**

Use:

```java
record ToolStarted(String invocationId, String toolId) implements AgentEvent {
    public ToolStarted {
        requireIdentity(invocationId, toolId);
    }
}

record ToolCompleted(
        String invocationId,
        String toolId,
        boolean failure,
        JsonObject normalized) implements AgentEvent {
    public ToolCompleted {
        requireIdentity(invocationId, toolId);
        normalized = Objects.requireNonNull(normalized, "normalized").deepCopy();
    }
}

private static void requireIdentity(String invocationId, String toolId) {
    if (invocationId == null || invocationId.isBlank()
            || toolId == null || toolId.isBlank()) {
        throw new IllegalArgumentException("tool invocation identity is required");
    }
}
```

- [x] **Step 4: Emit the model tool-call ID from the Agent loop**

In `executeTools(...)`, replace the events with:

```java
events.accept(new AgentEvent.ToolStarted(call.id(), exposedId));
// execute tool
events.accept(new AgentEvent.ToolCompleted(
        call.id(), result.toolId(), result.failure(), result.normalized()));
```

Keep the model tool-result continuation bound to the same `call.id()`.

- [x] **Step 5: Update all test fixtures to pass explicit IDs**

Use deterministic IDs such as `call-1`, `call-2`, and `call-recipe`. Never
generate an ID inside a reducer or infer it from the tool name.

- [x] **Step 6: Run Agent tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.agent.AgentEventTest' \
  --tests 'dev.tomewisp.agent.GameGuideAgentTest'
```

Expected: PASS.

- [x] **Step 7: Commit Agent invocation identity**

```bash
git add common/src/main/java/dev/tomewisp/agent/AgentEvent.java \
  common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java \
  common/src/test/java/dev/tomewisp/agent
git commit -m "feat: correlate guide tools by invocation id"
```

### Task 3: Version and verify the strict server event protocol

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/bridge/protocol/BridgeProtocol.java`
- Modify: `common/src/main/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodec.java`
- Modify: `common/src/test/java/dev/tomewisp/bridge/protocol/ServerAgentEventCodecTest.java`

- [x] **Step 1: Write failing protocol-v3 tests**

Add round trips for:

```java
AgentEvent.ToolStarted started = assertInstanceOf(
        AgentEvent.ToolStarted.class,
        codec.decode(codec.encode(request,
                new AgentEvent.ToolStarted("call-1", "tomewisp:get_recipe")), request));
assertEquals("call-1", started.invocationId());

AgentEvent.ToolCompleted completed = assertInstanceOf(
        AgentEvent.ToolCompleted.class,
        codec.decode(codec.encode(request,
                new AgentEvent.ToolCompleted(
                        "call-1", "tomewisp:get_recipe", false, new JsonObject())), request));
assertEquals("call-1", completed.invocationId());
```

Add a strict rejection test whose `tool_started` body omits `invocationId`.

- [x] **Step 2: Run the codec test and confirm schema failure**

```bash
./gradlew :common:test --tests 'dev.tomewisp.bridge.protocol.ServerAgentEventCodecTest'
```

Expected: FAIL because the codec still expects the version-2 fields.

- [x] **Step 3: Bump the bridge protocol**

Set:

```java
public static final int VERSION = 3;
```

No backwards decoder is added because the existing bridge is strict and both
loader artifacts ship together.

- [x] **Step 4: Update strict codec field sets**

Decode with:

```java
case "tool_started" -> read(
        body, Set.of("invocationId", "toolId"), AgentEvent.ToolStarted.class);
case "tool_completed" -> read(
        body,
        Set.of("invocationId", "toolId", "failure", "normalized"),
        AgentEvent.ToolCompleted.class);
```

Encoding remains Gson-based through the event record.

- [x] **Step 5: Run all bridge protocol tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.bridge.protocol.*'
```

Expected: PASS with version 3 and strict unknown/malformed rejection.

- [x] **Step 6: Commit the protocol upgrade**

```bash
git add common/src/main/java/dev/tomewisp/bridge/protocol \
  common/src/test/java/dev/tomewisp/bridge/protocol
git commit -m "feat: version chronological tool events"
```

### Task 4: Reduce events into chronological assistant and tool entries

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`

- [x] **Step 1: Replace the existing reducer test with an interleaved sequence**

Exercise this exact order:

```java
request = reducer.apply(request, text("I will inspect the recipe."), at(1));
request = reducer.apply(request,
        new AgentEvent.ToolStarted("call-1", "tomewisp:get_recipe"), at(2));
request = reducer.apply(request,
        new AgentEvent.ToolCompleted(
                "call-1", "tomewisp:get_recipe", false, groundedResult()), at(3));
request = reducer.apply(request, text("Now I will inspect inventory."), at(4));
request = reducer.apply(request,
        new AgentEvent.ToolStarted("call-2", "tomewisp:inspect_inventory"), at(5));
request = reducer.apply(request,
        new AgentEvent.ToolCompleted(
                "call-2", "tomewisp:inspect_inventory", false, groundedResult()), at(6));
request = reducer.apply(request, text("You are missing five ingots."), at(7));
request = reducer.apply(request,
        new AgentEvent.FinalText("You are missing five ingots."), at(8));
```

Assert entry classes and ordinals are exactly:

```java
assertEquals(
        List.of(
                GuideTimelineEntry.Assistant.class,
                GuideTimelineEntry.Tool.class,
                GuideTimelineEntry.Assistant.class,
                GuideTimelineEntry.Tool.class,
                GuideTimelineEntry.Assistant.class),
        request.timeline().stream().map(Object::getClass).toList());
assertEquals(List.of(0, 1, 2, 3, 4),
        request.timeline().stream().map(GuideTimelineEntry::ordinal).toList());
```

Add a repeated-tool test with two different invocation IDs and a missing-ID
completion test that expects terminal `timeline_protocol_error`.

- [x] **Step 2: Run the reducer tests and confirm flattened behavior fails**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideStateReducerTest'
```

Expected: FAIL because the reducer still accumulates one string and tool list.

- [x] **Step 3: Add reducer helpers for timeline append/update**

Implement helpers with immutable copies:

```java
private static List<GuideTimelineEntry> appendText(
        List<GuideTimelineEntry> timeline, String delta) {
    ArrayList<GuideTimelineEntry> next = new ArrayList<>(timeline);
    if (!next.isEmpty() && next.getLast() instanceof GuideTimelineEntry.Assistant assistant
            && assistant.streaming()) {
        next.set(next.size() - 1, new GuideTimelineEntry.Assistant(
                assistant.ordinal(), assistant.text() + delta, true, assistant.sources()));
    } else {
        next.add(new GuideTimelineEntry.Assistant(next.size(), delta, true, List.of()));
    }
    return List.copyOf(next);
}

private static List<GuideTimelineEntry> startTool(
        List<GuideTimelineEntry> timeline, AgentEvent.ToolStarted started) {
    ArrayList<GuideTimelineEntry> next = closeAssistant(timeline);
    int toolIndex = (int) next.stream()
            .filter(GuideTimelineEntry.Tool.class::isInstance).count();
    next.add(new GuideTimelineEntry.Tool(next.size(), new GuideToolActivity(
            started.invocationId(), toolIndex, started.toolId(),
            GuideToolStatus.RUNNING, null, List.of())));
    return List.copyOf(next);
}
```

`closeAssistant(...)` replaces a streaming last assistant with the same entry
and `streaming=false` before appending the tool.

- [x] **Step 4: Complete tools by exact invocation ID**

Search timeline tool entries for `activity.invocationId()`. Exactly one running
entry must match. Replace that entry at the same ordinal with succeeded/failed
activity and extracted sources. If zero or more than one match, return a
terminal failed snapshot with:

```java
new GuideFailure(
        "timeline_protocol_error",
        "Tool completion identity is missing or ambiguous: " + completed.invocationId())
```

Do not append a synthetic completion and do not match by tool name.

- [x] **Step 5: Reconcile only the final assistant segment**

For `FinalText`:

```java
ArrayList<GuideTimelineEntry> next = new ArrayList<>(timeline);
if (!next.isEmpty() && next.getLast() instanceof GuideTimelineEntry.Assistant assistant) {
    next.set(next.size() - 1, new GuideTimelineEntry.Assistant(
            assistant.ordinal(), completed.text(), false, assistant.sources()));
} else {
    next.add(new GuideTimelineEntry.Assistant(
            next.size(), completed.text(), false, List.of()));
}
timeline = List.copyOf(next);
```

Earlier assistant entries remain byte-for-byte unchanged.

- [x] **Step 6: Preserve request-level source aggregation and usage**

Continue extracting and sorting sources exactly as Phase 3 did. The producing
tool entry receives its sources, while request-level `sources()` remains the
deduplicated compatibility/index projection.

- [x] **Step 7: Run reducer tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideStateReducerTest'
```

Expected: PASS for interleaving, repeated tool IDs, source extraction, final
reconciliation, rate limits, and late-event suppression.

- [x] **Step 8: Commit chronological reduction**

```bash
git add common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java \
  common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java
git commit -m "feat: preserve chronological guide events"
```

### Task 5: Project and render the timeline in actual order

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`

- [x] **Step 1: Write a failing UI ordering test**

Create a completed request with assistant/tool/assistant/tool/assistant timeline
and assert the UI rows are:

```java
assertEquals(
        List.of(
                GuideUiRow.User.class,
                GuideUiRow.Assistant.class,
                GuideUiRow.Tool.class,
                GuideUiRow.Assistant.class,
                GuideUiRow.Tool.class,
                GuideUiRow.Assistant.class),
        view.rows().stream().map(Object::getClass).toList());
```

Assert each projected non-user row retains its timeline ordinal.

- [x] **Step 2: Run the UI view test and confirm current grouping fails**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideUiViewTest'
```

Expected: FAIL because `GuideUiView` currently emits one assistant row followed
by all tools.

- [x] **Step 3: Add ordinals to UI rows**

Use:

```java
record Assistant(
        UUID requestId,
        int ordinal,
        String text,
        boolean streaming,
        List<GuideSource> sources) implements GuideUiRow { ... }

record Tool(
        UUID requestId,
        int ordinal,
        GuideToolActivity activity) implements GuideUiRow {}
```

The user row has no timeline ordinal because it precedes request output.

- [x] **Step 4: Project timeline entries directly**

Replace the assistant/tools grouping with:

```java
rows.add(new GuideUiRow.User(request.requestId(), request.userMessage()));
for (GuideTimelineEntry entry : request.timeline()) {
    switch (entry) {
        case GuideTimelineEntry.Assistant assistant -> rows.add(
                new GuideUiRow.Assistant(
                        request.requestId(), assistant.ordinal(), assistant.text(),
                        assistant.streaming(), assistant.sources()));
        case GuideTimelineEntry.Tool tool -> rows.add(
                new GuideUiRow.Tool(
                        request.requestId(), tool.ordinal(), tool.activity()));
    }
}
```

Keep rate-limit and terminal status rows after current timeline content until
the later persistence plan promotes them to durable timeline entries.

- [x] **Step 5: Keep tool detail selection stable by invocation ID**

In `refreshDetail(...)`, find the replacement tool using
`activity.invocationId()` instead of record equality or tool name:

```java
.filter(tool -> tool.activity().invocationId().equals(selectedTool.invocationId()))
```

This lets a running card update in place without closing the detail drawer.

- [x] **Step 6: Run UI tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*'
```

Expected: PASS with chronological order and stable tool selection.

- [x] **Step 7: Commit the chronological UI projection**

```bash
git add common/src/main/java/dev/tomewisp/guide/ui \
  common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java \
  common/src/test/java/dev/tomewisp/guide/ui
git commit -m "feat: render interleaved agent timeline"
```

### Task 6: Preserve command, service, and end-to-end behavior

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideCommandFacade.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideServiceTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideCommandFacadeTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideProductE2ETest.java`
- Modify: `README.md`
- Modify: `docs/development.md`

- [x] **Step 1: Write a service-level interleaving test**

Extend `FakeLocal` to emit assistant text, two exact tool lifecycles, later
assistant text, and final text. Assert the snapshot timeline order and that the
session's terminal `GuideMessage` contains only the final assistant segment.

```java
assertEquals("final answer", request.assistantText());
assertEquals(5, request.timeline().size());
assertEquals("final answer", session.messages().getLast().text());
```

- [x] **Step 2: Write an E2E assertion for the long grounded chain**

In `GuideProductE2ETest`, assert that recipe search/detail/inventory/
craftability tool entries occupy their emitted positions and that any visible
assistant continuation after a tool appears after that tool entry.

- [x] **Step 3: Run focused guide tests and confirm failures**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideServiceTest' \
  --tests 'dev.tomewisp.guide.GuideCommandFacadeTest' \
  --tests 'dev.tomewisp.guide.GuideProductE2ETest'
```

Expected: FAIL until fixtures and terminal message behavior use the timeline.

- [x] **Step 4: Keep service history terminal-only and timeline-safe**

When a request becomes terminal, add a session assistant message only from
`after.assistantText()`. Do not concatenate intermediate explanatory segments
into model conversation history because the Agent's own `AgentSessionStore`
already retains provider turns and tool messages.

If reducer application produces terminal `timeline_protocol_error`, publish it
like every other structured failure and release the active session slot.

- [x] **Step 5: Update command tool notices without flattened indexes**

Track seen invocation IDs rather than only a count:

```java
Set<String> seenTools = new LinkedHashSet<>();
for (GuideToolActivity tool : request.tools()) {
    if (seenTools.add(tool.invocationId())) {
        notices.accept(GuideNotice.info("查询 " + tool.toolId()));
    }
}
```

The terminal command answer remains `request.assistantText()`.

- [x] **Step 6: Document the visible chronology**

Update README and development GUI sections to state that assistant segments and
tool cards render in actual event order, running cards update in place, and
reasoning remains hidden.

- [x] **Step 7: Run the focused guide suite**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.*'
```

Expected: PASS.

- [x] **Step 8: Run architecture and bridge coverage**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.ClientArchitectureTest' \
  --tests 'dev.tomewisp.bridge.protocol.*'
```

Expected: PASS.

- [x] **Step 9: Commit service and documentation integration**

```bash
git add common/src/main/java/dev/tomewisp/guide \
  common/src/test/java/dev/tomewisp/guide README.md docs/development.md
git commit -m "test: verify interleaved guide workflow"
```

### Task 7: Full Phase 4A verification

**Files:**
- Modify: `docs/superpowers/plans/2026-07-18-phase-4a-chronological-agent-timeline.md`

- [ ] **Step 1: Run the clean product gate**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Expected: all common tests pass and both loader artifacts build.

- [ ] **Step 2: Inspect artifacts and protocol metadata**

```bash
find fabric/build/libs neoforge/build/libs -maxdepth 1 -type f -name '*.jar' -print
git diff --check
git status --short --branch
```

Expected: production JARs exist, no whitespace errors, and no generated/runtime
files are staged.

- [ ] **Step 3: Mark completed checkboxes and record exact verification truth**

Update this plan with the commands run, test count, artifact names, and any
explicitly unrun graphical/live-provider checks. Do not claim the final modded
smoke; that belongs to the final Phase 4 verification plan.

- [ ] **Step 4: Commit the Phase 4A verification record**

```bash
git add docs/superpowers/plans/2026-07-18-phase-4a-chronological-agent-timeline.md
git commit -m "docs: record chronological timeline verification"
```
