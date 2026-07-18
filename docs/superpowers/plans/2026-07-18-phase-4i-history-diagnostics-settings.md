# Phase 4I History and Diagnostics Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the native settings package with transactional actor-scoped history administration, Debug Mode whole-database reset, live display configuration, friendly/debug diagnostics, and consolidated both-loader acceptance.

**Architecture:** `GuideService` blocks new actor mutations while it reserves one deletion against an idle ordered history repository. SQLite owns transactional current-partition/current-actor/all-database operations; successful completion resets matching in-memory state, while failure leaves it unchanged. `GuideDisplayRuntime` and a redacted diagnostics aggregator feed the common settings snapshot and screen without making the screen an orchestration owner.

**Tech Stack:** Java 25, SQLite JDBC, Gson strict config, immutable Guide/settings snapshots, Minecraft native UI, JUnit 5 race/rollback tests, Gradle Fabric/NeoForge builds

---

## Scope and file map

- History store/repository: transactional delete/reset APIs and atomic idle reservation.
- GuideService/Manager: active-request gate, no-new-write interval, in-memory reset, actor identity ownership.
- Display runtime: strict atomic Debug Mode save/reload and live Guide projection.
- Diagnostics: friendly normal cards plus separately gated redacted technical values.
- Settings UI: General, History, and Diagnostics sections; confirmation tokens and keyboard/narration.
- Verification: deterministic clean gate and retained graphical/live-provider evidence where actually run.

### Task 1: Add transactional history administration to SQLite

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryStore.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryDeleteScope.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`

- [x] **Step 1: Write isolation, rollback, and unsupported-schema reset tests**

```java
@Test
void deleteActorRemovesOnlyMatchingActorPartitions() {
    store.save(partition(actorA, scopeA1));
    store.save(partition(actorA, scopeA2));
    store.save(partition(actorB, scopeB));

    store.delete(GuideHistoryDeleteScope.actor(actorA));

    assertTrue(store.load(scopeA1).partition().isEmpty());
    assertTrue(store.load(scopeA2).partition().isEmpty());
    assertTrue(store.load(scopeB).partition().isPresent());
}

@Test
void resetUnsupportedSchemaIsExplicitTransactionalAndCreatesCurrentSchema() {
    createSchemaVersion(database, 999);
    assertThrowsCode(() -> store.load(scope), "history_schema_unsupported");
    store.resetDatabase();
    assertTrue(store.load(scope).partition().isEmpty());
    assertEquals(GuideHistoryPartition.SCHEMA_VERSION, readSchemaVersion(database));
}
```

Also inject SQL failure after deletion/drop but before commit and prove every
prior row/table remains. Cover current partition, actor-all, reset all actors,
malformed actor/scope rejection, and no deletion of external files/traces.

- [x] **Step 2: Run the SQLite tests and verify missing operations**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteGuideHistoryStoreTest'
```

- [x] **Step 3: Implement typed transactional deletion and reset**

```java
public sealed interface GuideHistoryDeleteScope {
    record Partition(GuideHistoryScope scope) implements GuideHistoryDeleteScope {}
    record Actor(UUID actorId) implements GuideHistoryDeleteScope {}
    static Partition partition(GuideHistoryScope scope) { return new Partition(scope); }
    static Actor actor(UUID actorId) { return new Actor(actorId); }
}

public interface GuideHistoryStore extends AutoCloseable {
    GuideHistoryLoad load(GuideHistoryScope scope);
    void save(GuideHistoryPartition partition);
    void delete(GuideHistoryDeleteScope scope);
    void resetDatabase();
}
```

Partition/actor deletes use `delete from partitions` inside one transaction;
foreign-key cascades remove children. Reset opens a raw configured connection
without `ensureSchema`, disables foreign keys for the transaction, enumerates
and safely quotes every non-`sqlite_%` table from `sqlite_master`, drops them,
creates the one current schema, and commits. On any failure rollback and throw
`history_delete_failed`. Never delete the database/WAL files piecemeal.

- [x] **Step 4: Run store/runtime compatibility tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteGuideHistoryStoreTest' \
  --tests 'dev.tomewisp.guide.history.SqliteRuntimeCompatibilityTest'
git add common/src/main/java/dev/tomewisp/guide/history \
  common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java
git commit -m "feat: transactionally administer guide history"
```

### Task 2: Reserve deletion only when the ordered repository is idle

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryAccess.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryRepository.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java`

- [x] **Step 1: Write pending-write, concurrent delete, close, and resurrection tests**

```java
@Test
void deleteRejectsInsteadOfQueueingBehindPendingSave() {
    store.blockSave();
    CompletableFuture<Void> save = repository.save(partition());

    GuideHistoryException failure = assertFutureFailure(
            repository.delete(GuideHistoryDeleteScope.actor(actor)));
    assertEquals("history_delete_busy", failure.code());
    assertEquals(0, store.deleteCalls());

    store.releaseSave();
    save.join();
}

@Test
void reservedDeleteBlocksNewSaveAndCannotBeResurrected() {
    store.blockDelete();
    CompletableFuture<Void> deleting = repository.delete(scope());
    assertFutureCode(repository.save(partition()), "history_delete_busy");
    store.releaseDelete();
    deleting.join();
    assertFalse(store.contains(scopeId));
}
```

- [x] **Step 2: Run repository tests and observe current unconditional queueing**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.GuideHistoryRepositoryTest'
```

- [x] **Step 3: Implement atomic reservation separate from the ordered future**

```java
public interface GuideHistoryAccess {
    CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope);
    CompletableFuture<Void> save(GuideHistoryPartition partition);
    CompletableFuture<Void> delete(GuideHistoryDeleteScope scope);
    CompletableFuture<Void> resetDatabase();
    CompletableFuture<Void> flush();
    GuideHistoryActivity activity();
}

public record GuideHistoryActivity(int pendingWrites, boolean deleting) {
    public boolean idleForDeletion() { return pendingWrites == 0 && !deleting; }
}
```

Inside one synchronized block, `save` rejects while `deleting`, increments
`pendingWrites` before submission, and decrements on completion. `delete` and
`resetDatabase` require `pendingWrites == 0 && !deleting`, set `deleting=true`
before submission, and clear it in completion. A second operation never queues.
Load/close retain existing ordering and structured repository-closed behavior.

- [x] **Step 4: Run history repository/store tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.*'
git add common/src/main/java/dev/tomewisp/guide/history \
  common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java
git commit -m "feat: reserve idle history deletion"
```

### Task 3: Coordinate actor-scoped deletion through GuideService

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideServiceManager.java`
- Create: `common/src/main/java/dev/tomewisp/guide/GuideHistoryAdministration.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideServiceHistoryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideServiceManagerHistoryTest.java`

- [x] **Step 1: Write active request, no-new-write, reset, and failure-retention races**

```java
@Test
void actorDeleteRejectsAnyActiveSessionWithoutCancellingIt() {
    service.selectSession("other").join();
    UUID active = success(service.ask("question").join()).value();

    assertFailure(service.deleteActorHistory().join(), "history_delete_busy");
    assertEquals(active, service.snapshot().sessions().stream()
            .flatMap(session -> session.requests().stream())
            .filter(request -> !request.terminal()).findFirst().orElseThrow().requestId());
    assertEquals(0, history.deleteCalls());
}

@Test
void successfulDeleteResetsMemoryBeforeNewWritesAreAccepted() {
    CompletableFuture<ToolResult<Boolean>> deleting = service.deleteCurrentHistory();
    assertFailure(service.ask("during delete").join(), "history_delete_busy");
    history.completeDelete();
    assertSuccess(deleting.join());
    assertEquals(List.of("main"), sessionIds(service.snapshot()));
    assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    assertTrue(local.clearedActors().contains(actor));
}
```

Cover pending repository write, SQL failure preserving exact snapshot, current
partition vs actor-all call, foreign actor impossibility, delete after screen
detach, disconnect race, and reset requiring Debug Mode + fresh second token.

- [x] **Step 2: Run Guide history/manager tests and verify missing actions**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideServiceHistoryTest' \
  --tests 'dev.tomewisp.guide.GuideServiceManagerHistoryTest'
```

- [x] **Step 3: Implement Guide-owned gates and reset after commit**

```java
public interface GuideHistoryAdministration {
    CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory();
    CompletableFuture<ToolResult<Boolean>> deleteActorHistory();
}

public final class GuideService implements GuideHistoryAdministration {
    private boolean historyDeletionPending;
    public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory();
    public CompletableFuture<ToolResult<Boolean>> deleteActorHistory();
}
```

On the dispatcher thread: reject if loading/unavailable, any session has an
active request, persistence is saving, repository activity is not idle, or a
deletion is already pending. Set `historyDeletionPending` before calling the
repository so every state-changing Guide action rejects with
`history_delete_busy`. On successful completion clear local model actor
sessions, rebuild one clean `main` session using current default selection,
reset persistence to available, publish without scheduling a save, then release
the gate. On failure release the gate and retain the prior snapshot exactly.

`GuideServiceManager.resetHistoryDatabase()` checks current service active state
plus global repository activity, calls reset, then resets the current service
only after commit. It is not exposed to the Screen; `ClientSettingsService`
validates the Debug Mode one-use second-confirmation token before invoking it.

- [x] **Step 4: Run state/race tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideService*Test' \
  --tests 'dev.tomewisp.guide.history.*'
git add common/src/main/java/dev/tomewisp/guide \
  common/src/test/java/dev/tomewisp/guide/GuideServiceHistoryTest.java \
  common/src/test/java/dev/tomewisp/guide/GuideServiceManagerHistoryTest.java
git commit -m "feat: coordinate player history deletion"
```

### Task 4: Make display settings atomic and live

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayConfigWriter.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayConfigLoader.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/ui/GuideDisplayConfigLoaderTest.java`
- Create: `common/src/test/java/dev/tomewisp/guide/ui/GuideDisplayRuntimeTest.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java`

- [x] **Step 1: Write writer/runtime/live-projection tests**

```java
@Test
void failedDebugSaveRetainsFileAndProjection() {
    GuideDisplayRuntime runtime = runtimeWithFailingMove(false);
    assertFailure(runtime.save(new GuideDisplayConfig(1, true)), "settings_write_failed");
    assertFalse(runtime.config().debugMode());
    assertFalse(Files.readString(displayPath).contains("true"));
}

@Test
void successfulToggleRebuildsCurrentGuideProjectionWithoutHistoryWrite() {
    display.save(new GuideDisplayConfig(1, true)).join();
    screen.renderFrom(service.snapshot());
    assertTrue(screen.detail().debug().isPresent());
    assertEquals(0, history.saveCallsAfterToggle());
}
```

- [x] **Step 2: Run display/GUI tests and verify static config behavior**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideDisplay*Test' \
  --tests 'dev.tomewisp.client.gui.TomeWispScreenProjectionTest'
```

- [x] **Step 3: Implement canonical writer and atomic runtime**

```java
public final class GuideDisplayRuntime {
    public GuideDisplayConfig config();
    public GuideFailure failure();
    public ToolResult<GuideDisplayConfig> save(GuideDisplayConfig candidate);
    public ToolResult<GuideDisplayConfig> reload();
}
```

Use `AtomicSettingsFile`, publish only after successful replace/load, and retain
last valid runtime on error. `TomeWispScreen` receives the runtime or display
supplier and constructs `GuideUiView.from(snapshot, display.config())` on each
projection rather than retaining the launch-time record.

- [x] **Step 4: Run display/history privacy tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.guide.history.*'
git add common/src/main/java/dev/tomewisp/guide/ui \
  common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java \
  common/src/test/java/dev/tomewisp/guide/ui \
  common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java
git commit -m "feat: apply live Debug Mode settings"
```

### Task 5: Add redacted normal/debug diagnostics snapshots

**Files:**
- Create: `common/src/main/java/dev/tomewisp/settings/diagnostics/SettingsDiagnosticsSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/settings/diagnostics/SettingsDiagnosticCard.java`
- Create: `common/src/main/java/dev/tomewisp/settings/diagnostics/SettingsDiagnosticsAggregator.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsService.java`
- Create: `common/src/test/java/dev/tomewisp/settings/diagnostics/SettingsDiagnosticsAggregatorTest.java`
- Modify: `common/src/test/java/dev/tomewisp/settings/ClientSettingsServiceTest.java`

- [x] **Step 1: Write normal/debug data-class and redaction tests**

```java
@Test
void normalDiagnosticsCannotRepresentTechnicalOrSecretFields() {
    SettingsDiagnosticsSnapshot normal = aggregator.snapshot(false, inputs());
    String rendered = normal.toString();
    assertFalse(rendered.contains("requestId"));
    assertFalse(rendered.contains("scope_id"));
    assertFalse(rendered.contains("secret-value"));
    assertFalse(rendered.contains("authorization"));
    assertFalse(rendered.contains("reasoning"));
    assertTrue(normal.cards().stream().allMatch(card -> card.friendlyStatus() != null));
}

@Test
void debugAddsOnlyApprovedRedactedTechnicalProjection() {
    SettingsDiagnosticsSnapshot debug = aggregator.snapshot(true, inputs());
    assertTrue(debug.debug().isPresent());
    assertEquals(GuideHistoryPartition.SCHEMA_VERSION,
            debug.debug().orElseThrow().databaseSchema());
    assertFalse(debug.toString().contains(rawDatabasePath));
}
```

Cover model profile/status and redacted authority, metadata state, capability
and source counts/generations, current actor history state, selected request
queue/429, compaction checkpoints/token estimates, and absent/unavailable
domains. Never include assistant reasoning or provider body.

- [x] **Step 2: Run diagnostics tests and verify missing snapshot types**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.diagnostics.*' \
  --tests 'dev.tomewisp.settings.ClientSettingsServiceTest'
```

- [x] **Step 3: Implement typed aggregation from existing immutable snapshots**

```java
public record SettingsDiagnosticsSnapshot(
        List<SettingsDiagnosticCard> cards,
        Optional<DebugSettingsDiagnostics> debug) {}

public final class SettingsDiagnosticsAggregator {
    public SettingsDiagnosticsSnapshot snapshot(
            boolean debugMode,
            DiagnosticsInputs inputs) {
        // Project only immutable/redacted domain snapshots supplied by settings.
    }
}
```

The aggregator performs no provider/file/SQL/registry calls and owns no state.
Normal and debug records use distinct types so forbidden fields are
unrepresentable in normal mode. Redact endpoint to scheme + authority and
history scope to a friendly world/server kind label.

- [x] **Step 4: Run privacy/settings tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.trace.*'
git add common/src/main/java/dev/tomewisp/settings \
  common/src/test/java/dev/tomewisp/settings
git commit -m "feat: expose redacted settings diagnostics"
```

### Task 6: Complete General, History, and Diagnostics native pages

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispSettingsScreen.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsService.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/GeneralSettingsProjection.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/HistorySettingsProjection.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/DiagnosticsSettingsProjection.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispSettingsScreenProjectionTest.java`
- Modify: `common/src/test/java/dev/tomewisp/settings/ClientSettingsServiceTest.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/settings/HistorySettingsProjectionTest.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/settings/DiagnosticsSettingsProjectionTest.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`

- [x] **Step 1: Write confirmation/accessibility/normal-debug UI tests**

Test Debug Mode default/toggle, current partition vs actor-all labels,
busy-disabled actions, first confirmation, distinct reset second confirmation,
stale token rejection, listener detach during delete, friendly normal cards,
debug separation, no foreign actor IDs, narrow/wide navigation, keyboard focus,
narration, and color-independent statuses.

```java
@Test
void wholeDatabaseResetRequiresDebugModeAndFreshSecondConfirmation() {
    assertFalse(projection(false).actions().contains(RESET_DATABASE));
    HistorySettingsProjection debug = projection(true);
    ConfirmationToken first = debug.requestResetConfirmation();
    assertFailure(debug.reset(null), "history_delete_confirmation_required");
    assertFailure(debug.reset(first.expired()), "history_delete_confirmation_required");
    assertSuccess(debug.reset(debug.confirmAgain(first)));
}
```

- [x] **Step 2: Run settings UI tests and verify missing pages**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*Settings*'
```

- [x] **Step 3: Implement native pages and one-use confirmation tokens**

General renders only implemented presentation settings. History renders
friendly persistence health and actor-scoped actions. Diagnostics renders card
projections; Debug Mode adds the separate technical area. Confirmation tokens
are screen-local, action/scope-bound, one-use, and invalidated on navigation,
draft change, screen close, or snapshot generation change.

The screen passes no actor/scope/path/SQL strings into actions; it calls typed
service methods bound to the current GuideService. `ClientSettingsService`
returns `history_delete_confirmation_required` unless the reset token is the
fresh second-stage token for the current snapshot and Debug Mode remains on;
only then may it call `GuideServiceManager.resetHistoryDatabase()`.

- [x] **Step 4: Run GUI/localization/privacy tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.guide.*'
git add common/src/main/java/dev/tomewisp/client/gui \
  common/src/test/java/dev/tomewisp/client/gui \
  common/src/main/resources/assets/tomewisp/lang
git commit -m "feat: complete history and diagnostics settings"
```

### Task 7: Wire lifecycle parity and consolidated settings verification

**Files:**
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/decisions/2026-07-18-014-history-administration.md`
- Modify: `docs/isme/decisions/2026-07-18-016-native-settings-coordination.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/superpowers/plans/2026-07-18-phase-4i-history-diagnostics-settings.md`

- [ ] **Step 1: Add parity/shutdown assertions**

Assert both entrypoints supply the same `GuideDisplayRuntime`, settings service,
GuideServiceManager history-administration binding, and config/database paths.
Shutdown must close/detach settings, cancel a probe, disconnect GuideService,
flush/close history, close metadata, and close model resources without blocking
the client thread. Common imports no loader APIs.

- [ ] **Step 2: Run the deterministic clean product gate and security checks**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
git diff --check
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
```

Run JSON syntax checks, common/loader boundary assertions, production-JAR scans
for credential patterns/test secrets, and history-schema inspection proving
there is one current pre-release schema with no migration code. Record exact
test totals and loader artifact SHA-256 hashes.

- [ ] **Step 3: Execute retained graphical/live settings acceptance when environment permits**

Launch graphical clients only under the approved real-client harness. Exercise
both loaders' settings navigation, wide/narrow layout, Debug Mode, model draft
save/reload, one explicitly authorized provider probe, Knowledge & Capabilities
cards, recipe Tool child page with every actually installed compatible viewer,
history deletion using test data, busy rejection, and Debug reset confirmation.

The provider key must already be present as a named environment variable; never
copy it from conversation into argv, files, logs, screenshots, or reports.
Retain redacted report JSON, artifact/mod versions, source URLs, SHA-256 values,
and screenshots that contain no credentials/provider bodies. If a target viewer
is unavailable, record that fact rather than claiming it through generic code.

- [ ] **Step 4: Update docs, decision commit references, and commit evidence**

Document all settings pages, stable failures, history scope, reset safety,
normal/debug data classes, runtime reload behavior, and exact manual/live truth.

```bash
git add fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java \
  neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java \
  common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java \
  README.md docs/development.md docs/isme \
  docs/superpowers/plans/2026-07-18-phase-4i-history-diagnostics-settings.md \
  docs/verification
git commit -m "docs: verify native settings and diagnostics"
```

Do not stage `docs/verification/.DS_Store` or any ignored run/config/credential
file.

## Completion boundary

Phase 4I is complete when actor-scoped history deletion and Debug reset obey
busy/rollback/no-resurrection semantics, Debug Mode updates live, normal/debug
diagnostics remain privacy-safe, every native settings section is usable on
both loaders, and deterministic plus retained manual evidence supports the
claims. This completes the settings/diagnostics work package only; remaining
Phase 4 semantic rich components, long-history paging/performance, and final
overall completion audit continue afterward.
