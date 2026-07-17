# Phase 4B Durable History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a packaged, versioned SQLite history database that restores the exact visible Agent timeline within a player/world/server partition, recovers orphaned work as interrupted, and never blocks a Minecraft-owned thread.

**Architecture:** Common code owns strict durable records, schema migration, an asynchronous single-writer repository, and GuideService hydration/persistence semantics. A Minecraft-common scope resolver captures the current player and connection discriminator on the client thread, immediately hashes it into an immutable scope, and loader entrypoints provide only database/config paths. SQLite remains behind `java.sql`; Fabric and NeoForge embed the same verified driver without loader-specific persistence behavior.

**Tech Stack:** Java 25 records, JDBC, Xerial SQLite JDBC 3.50.3.0, Gson strict JSON payloads, JUnit 5 temporary databases, Fabric nested JARs, NeoForge jar-in-jar packaging.

---

## Accepted Boundaries

- This is an internal work package in the single Phase 4, not a separate product phase.
- SKMB-2026-07-18-005 owns durable data, interruption, privacy, deletion, and failure semantics.
- SKMB-2026-07-18-006 records the reviewable execution defaults used by this plan.
- This plan stores the current normal-mode player-visible projection. Developer capture, compaction checkpoints, semantic message schema additions, and history paging extend the versioned repository in later Phase 4 plans.
- It introduces no age, count, byte, queue, or database-size cap.

## File Map

- Modify `gradle.properties`, `common/build.gradle`, `fabric/build.gradle`, and `neoforge/build.gradle` for one packaged SQLite version.
- Create `common/src/main/java/dev/tomewisp/guide/history/` for scope, durable records, strict codec, JDBC store, and asynchronous repository.
- Create `GuideToolPresentation` and modify `GuideToolActivity`/`GuideToolPresenter` so durable normal-mode cards retain only presentation lines, never full normalized results.
- Create `common/src/main/java/dev/tomewisp/guide/GuidePersistenceSnapshot.java` and update GuideService snapshots/status.
- Create `common/src/main/java/dev/tomewisp/client/MinecraftGuideHistoryScope.java` and wire both loader client entrypoints.
- Create `scripts/verify-sqlite-packaging.sh` for retained production-JAR runtime proof.
- Add focused history, repository, recovery, race, scope, UI, architecture, and loader tests.
- Update both translations, README, development guide, Phase 4 design status, and ISME commit references.

### Task 1: Prove SQLite Runtime and Loader Packaging

**Files:**
- Modify: `gradle.properties`
- Modify: `common/build.gradle`
- Modify: `fabric/build.gradle`
- Modify: `neoforge/build.gradle`
- Create: `common/src/test/java/dev/tomewisp/guide/history/SqliteRuntimeCompatibilityTest.java`
- Create: `scripts/verify-sqlite-packaging.sh`

- [x] **Step 1: Add a failing Java 25 JDBC test**

Create a temporary-file test using only `java.sql` production APIs:

```java
@Test
void opensWalDatabaseAndCommitsTransaction() throws Exception {
    Path database = temporary.resolve("history.sqlite3");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
        try (Statement statement = connection.createStatement()) {
            assertEquals("wal", statement.executeQuery("pragma journal_mode=wal")
                    .getString(1).toLowerCase(Locale.ROOT));
            statement.execute("create table proof(id integer primary key, value text not null)");
        }
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into proof(value) values (?)")) {
            insert.setString(1, "java25");
            insert.executeUpdate();
        }
        connection.commit();
    }
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("select value from proof")) {
        assertTrue(result.next());
        assertEquals("java25", result.getString(1));
    }
}
```

- [x] **Step 2: Prove the driver is absent**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteRuntimeCompatibilityTest'
```

Expected: FAIL with `No suitable driver found for jdbc:sqlite`.

- [x] **Step 3: Add exact runtime and embedded dependencies**

Add `sqlite_jdbc_version=3.50.3.0` to `gradle.properties`, then:

```groovy
// common/build.gradle
testRuntimeOnly("org.xerial:sqlite-jdbc:${sqlite_jdbc_version}")

// fabric/build.gradle
implementation(include("org.xerial:sqlite-jdbc:${sqlite_jdbc_version}"))

// neoforge/build.gradle
implementation("org.xerial:sqlite-jdbc:${sqlite_jdbc_version}")
jarJar("org.xerial:sqlite-jdbc:${sqlite_jdbc_version}")
```

If ModDevGradle rejects the last notation, use its documented
`jarJar(implementation(...))` equivalent. Do not unpack or shade native files.

- [x] **Step 4: Run the JDBC test and both loader builds**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteRuntimeCompatibilityTest' \
  :fabric:build :neoforge:build
```

Expected: PASS and both production loader JARs build.

- [x] **Step 5: Add and run the production-JAR verifier**

The shell script must locate each production mod JAR, extract its nested driver
to a temporary directory, verify the Xerial class plus Linux/Mac/Windows
`x86_64` and `aarch64` natives, and launch a Java 25 `select sqlite_version()`
probe using that extracted driver. Xerial's optional SLF4J API/NOP support may
be downloaded into the temporary proof directory with its own SHA-256, because
Minecraft supplies SLF4J at runtime; the SQLite driver itself must come only
from the production mod artifact and never Gradle's cache. Print all artifact
hashes and delete no user or repository files.

```bash
bash -n scripts/verify-sqlite-packaging.sh
./scripts/verify-sqlite-packaging.sh
```

Expected: both loaders report SQLite `3.50.3` and all six required targets.

- [x] **Step 6: Commit the packaging proof**

```bash
git add gradle.properties common/build.gradle fabric/build.gradle neoforge/build.gradle \
  common/src/test/java/dev/tomewisp/guide/history/SqliteRuntimeCompatibilityTest.java \
  scripts/verify-sqlite-packaging.sh
git commit -m "build: package sqlite history runtime"
```

Verification on 2026-07-18 used Java 25.0.2. The red test failed at the
initial JDBC connection before the runtime dependency was present. After
packaging, the focused JDBC test and both loader builds passed in 16 seconds.
The production-JAR proof loaded SQLite `3.50.3` from each extracted nested JAR
and verified Linux, macOS, and Windows x86_64/aarch64 native entries.

Fabric production JAR SHA-256:
`4f8476a3b6d4f7461e0c305327cd9874c0bdd95dc481f304531341a110d82c2b`.
NeoForge production JAR SHA-256:
`09671a45ea395c927eadf9a5a777693f1b61cb3926ecdb0390960d036e468b1b`.
The build emits a non-fatal Fabric metadata warning for Xerial's four-component
version and Java 25 emits a restricted native-access warning. Both artifacts
still registered and executed their own nested driver; final real-client smoke
must retain the warning behavior as compatibility evidence.

### Task 2: Define Strict Durable Records

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryScope.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPartition.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryLoad.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideToolPresenter.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryCodecTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/ui/GuideToolPresenterTest.java`

- [x] **Step 1: Write failing scope and round-trip tests**

Cover deterministic hashing, actor and connection isolation, absence of raw
address/path in `scopeId`, exact assistant/tool chronology, evidence, usage,
failures, and unknown/missing JSON rejection:

```java
GuideHistoryScope first = GuideHistoryScope.derive(
        actor, GuideHistoryScope.Kind.MULTIPLAYER, "Example.COM:25565");
GuideHistoryScope same = GuideHistoryScope.derive(
        actor, GuideHistoryScope.Kind.MULTIPLAYER, "example.com:25565");
assertEquals(first, same);
assertFalse(first.scopeId().contains("example"));

String json = codec.encodeTimeline(timeline);
List<GuideTimelineEntry> restored = codec.decodeTimeline(json);
GuideToolActivity restoredTool = ((GuideTimelineEntry.Tool) restored.get(1)).activity();
assertNull(restoredTool.normalized());
assertEquals(List.of("配方: minecraft:iron_block", "输出: 1"),
        restoredTool.presentationLines());

JsonArray malformed = JsonParser.parseString(json).getAsJsonArray();
malformed.get(0).getAsJsonObject().addProperty("unknown", true);
assertThrows(IllegalArgumentException.class,
        () -> codec.decodeTimeline(malformed.toString()));
```

- [x] **Step 2: Run and confirm the domain is absent**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.GuideHistoryCodecTest'
```

Expected: compilation fails because the history records do not exist.

- [x] **Step 3: Implement immutable scope and partition records**

`GuideHistoryScope.derive` lowercases and trims multiplayer addresses,
normalizes absolute single-player paths, hashes UTF-8 bytes with SHA-256, and
retains only actor ID, kind, and lowercase hex scope ID.

`GuideHistoryPartition` stores schema version, scope, selected session, model
mode, immutable session snapshots, and update time. Validate every request
belongs to its containing session and every message references a same-session
request. Do not store capabilities, inventory, recipe generations, or live
objects.

- [x] **Step 4: Implement a manual strict codec**

Use `JsonParser` and exact field-set checks for every object. Encode `Instant`
as ISO-8601, enums by exact name, and normalized JSON as deep copies. Do not use
reflective Gson binding for time types. Unknown or missing schema-v1 fields fail.

Add immutable `presentationLines` to `GuideToolActivity`. The reducer computes
them with `GuideToolPresenter.lines(toolId, normalized)` when a tool completes;
the screen reads these lines first and only derives them from normalized data
for legacy/runtime fixtures. Normal-mode durable payloads set `normalized` to
null and retain invocation ID, index, tool ID, status, sources, and presentation
lines. The codec has no representable field for reasoning, credentials,
authorization headers, cookies, or raw provider bodies.

- [x] **Step 5: Run strict codec tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.GuideHistoryCodecTest'
```

Expected: PASS for complete round trips and explicit malformed failures.

- [x] **Step 6: Commit the durable domain**

```bash
git add common/src/main/java/dev/tomewisp/guide/history \
  common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java \
  common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java \
  common/src/main/java/dev/tomewisp/guide/ui/GuideToolPresenter.java \
  common/src/test/java/dev/tomewisp/guide/history/GuideHistoryCodecTest.java \
  common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java \
  common/src/test/java/dev/tomewisp/guide/ui/GuideToolPresenterTest.java
git commit -m "feat: define strict durable guide history"
```

### Task 3: Implement Schema Version 1 and Transactions

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryStore.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryException.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestStatus.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`

- [x] **Step 1: Write failing store tests**

Test new database migration, repeated open, exact partition isolation,
replacement without cross-partition mutation, deletion through replacement,
rollback after an injected constraint failure, active-request recovery, corrupt
row isolation, and rejection of a future schema version.

```java
store.save(alpha);
store.save(beta);
assertEquals(alpha, store.load(alpha.scope()).partition());
assertEquals(beta, store.load(beta.scope()).partition());

GuideRequestSnapshot request = store.load(active.scope()).partition()
        .sessions().getFirst().requests().getFirst();
assertEquals(GuideRequestStatus.INTERRUPTED, request.status());
assertEquals("request_interrupted", request.failure().code());
```

- [x] **Step 2: Run and confirm the store is absent**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteGuideHistoryStoreTest'
```

Expected: compilation fails on missing store types.

- [x] **Step 3: Create schema version 1 transactionally**

Create `schema_metadata`, `partitions`, `sessions`, `messages`, `requests`,
`timeline_entries`, and `request_sources`. Use composite foreign keys with
`ON DELETE CASCADE`, ordinal constraints, and indexes for partition/session
updated time and request/timeline order. Enable `foreign_keys=on`,
`journal_mode=wal`, and `synchronous=full` on every connection.

Store complex timeline/evidence payloads through `GuideHistoryCodec`, not Java
serialization. Keep `capture_mode='NORMAL'` explicit for later migration.

- [x] **Step 4: Implement atomic save, load, and recovery**

`save` replaces exactly one partition inside one transaction. `load` validates
all row relationships and codec payloads. It converts every nonterminal request
to `INTERRUPTED`, adds `request_interrupted`, sets terminal time from the
injected clock, commits the recovered projection, and preserves timeline order
and at-loss tool status.

Corrupt rows produce a partition-scoped diagnostic and remain untouched. Never
delete or repair them by guessing, and never block other partitions.

- [x] **Step 5: Run store tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteGuideHistoryStoreTest'
git add common/src/main/java/dev/tomewisp/guide/history \
  common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java
git commit -m "feat: persist versioned guide history"
```

Expected: tests pass before the commit.

### Task 4: Serialize Database Work Off the Client Thread

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryException.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryRepository.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java`

- [x] **Step 1: Write failing ordering and failure tests**

Use a blocking fake store to prove operations run on the named history worker,
saves preserve submission order, `flush` waits through the latest generation,
failures remain structured, and close rejects new work after completing prior
work:

```java
CompletableFuture<Void> first = repository.save(scope, snapshotAt(1));
CompletableFuture<Void> second = repository.save(scope, snapshotAt(2));
repository.flush().join();
assertEquals(List.of(1L, 2L), store.savedGenerations());
assertNotEquals(Thread.currentThread().threadId(), store.threadIds().getFirst());
```

- [x] **Step 2: Run and confirm the repository is absent**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.GuideHistoryRepositoryTest'
```

Expected: compilation fails on the repository types.

- [x] **Step 3: Implement the single-writer facade**

Use:

```java
Executors.newSingleThreadExecutor(Thread.ofPlatform()
        .name("tomewisp-history-", 0).daemon(true).factory())
```

Expose `load`, `save`, `flush`, and `closeAsync` as futures. Do not add a fixed
queue limit or another product-state owner. Preserve interruption and map SQL
failures to `history_open_failed`, `history_load_failed`,
`history_write_failed`, or `history_schema_unsupported` without raw paths or SQL
parameters in player-facing messages.

- [x] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.GuideHistoryRepositoryTest'
git add common/src/main/java/dev/tomewisp/guide/history \
  common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java
git commit -m "feat: serialize guide history transactions"
```

Expected: ordering, caller-thread isolation, flush, and close tests pass.

### Task 5: Hydrate and Persist GuideService

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/GuidePersistenceSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestStatus.java` (already adds `INTERRUPTED` in Task 3)
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideServiceManager.java`
- Create: `common/src/test/java/dev/tomewisp/guide/GuideServiceHistoryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideServiceTest.java`

- [x] **Step 1: Write failing lifecycle and race tests**

Cover loading visibility, `history_loading` rejection, hydration, partition
mismatch, ordered per-event saves, failure that keeps in-memory requests usable
but unsaved, stale completion suppression, explicit interrupted retry with a
new ID, clear/close durability, and disconnect that does not persist empty state.

```java
assertEquals(GuidePersistenceSnapshot.State.LOADING,
        service.snapshot().persistence().state());
assertFailure(service.ask("too early").join(), "history_loading");
history.completeLoad(restored);
dispatcher.runAll();
assertEquals(GuideRequestStatus.INTERRUPTED, restoredRequest(service).status());
UUID retry = success(service.retry(restoredId).join());
assertNotEquals(restoredId, retry);
```

- [x] **Step 2: Run and confirm missing persistence state**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideServiceHistoryTest' \
  --tests 'dev.tomewisp.guide.GuideServiceTest'
```

Expected: compilation fails on new persistence constructors and status.

- [x] **Step 3: Add UI-safe persistence state and interruption**

Expose only `DISABLED`, `LOADING`, `SAVING`, `AVAILABLE`, or `UNAVAILABLE`, the
latest submitted/committed generation, and an optional stable `GuideFailure`.
Add it to `GuideSnapshot`. Add `INTERRUPTED` to `GuideRequestStatus`; terminal
truth continues to use `terminalAt`.

- [x] **Step 4: Hydrate before normal actions**

Construct the service with its exact scope and repository. Load immediately,
marshal completion through `ClientEventDispatcher`, validate scope, replace
only sessions/messages/requests, rebuild request correlation, and publish
`AVAILABLE`. Never restore capabilities, recipes, inventory, listeners, or live
Agent session objects.

While loading, reject ask, retry, session/model mutations, clear, and close as
`history_loading`. On load failure, publish `UNAVAILABLE` and permit in-memory
actions with unsaved diagnostics.

- [x] **Step 5: Persist accepted transitions by generation**

After request creation and every reducer, session, model, clear, and close
mutation, detach a normal-mode `GuideHistoryPartition` and submit it. Mark
`SAVING`; only the newest generation can change health. Never wait on repository
work from the dispatcher. Strip full normalized tool output before submission.

- [x] **Step 6: Preserve history on disconnect**

Cancel active work through the reducer, submit terminal state, and clear only
connection maps without scheduling the empty snapshot. Return a future that
settles after the latest write; do not block the disconnect callback. Late Agent
or persistence completions cannot mutate a replacement service/scope.

- [x] **Step 7: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideServiceHistoryTest' \
  --tests 'dev.tomewisp.guide.GuideServiceTest' \
  --tests 'dev.tomewisp.guide.GuideCommandFacadeTest'
git add common/src/main/java/dev/tomewisp/guide \
  common/src/test/java/dev/tomewisp/guide
git commit -m "feat: restore durable guide sessions"
```

Expected: hydration, retry, failure, race, deletion, and disconnect tests pass.

Verification on 2026-07-18 covered loading rejection, interrupted hydration and
retry, sanitized per-event saves, stale completion suppression, nonfatal load
and write failures, disconnect cancellation durability, and replacement-scope
ordering with a queued client dispatcher. The focused service/command/product
E2E selection passed, followed by the full common suite with 148 tests,
0 failures, 0 errors, and 1 existing skip. Java 25 continued to emit the
already-recorded Xerial restricted native-access warning.

### Task 6: Resolve Scope and Wire Both Loaders

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/MinecraftGuideHistoryScope.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Create: `common/src/test/java/dev/tomewisp/client/MinecraftGuideHistoryScopeTest.java`
- Modify: `common/src/test/java/dev/tomewisp/architecture/CommonLoaderIsolationTest.java`

- [x] **Step 1: Write failing detached-scope tests**

Test single-player, multiplayer normalization, actor separation, no active
connection, and that results retain no live Minecraft object or raw path/address.

- [x] **Step 2: Run and confirm the resolver is absent**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.MinecraftGuideHistoryScopeTest'
```

Expected: compilation fails on the missing resolver.

- [x] **Step 3: Capture scope only on the client thread**

For integrated worlds use `Minecraft.getSingleplayerServer().getWorldPath(
LevelResource.ROOT).toAbsolutePath().normalize()`. For multiplayer use
`Minecraft.getCurrentServer().ip`. Read player UUID in that same call and
immediately pass detached strings to `GuideHistoryScope.derive`. Return
`history_scope_unavailable` when no world/server is active.

- [x] **Step 4: Construct one repository in each loader**

Use loader config directories for `tomewisp/history.sqlite3`. Both entrypoints
instantiate the common store/repository and resolver. Loader code may locate
paths and lifecycle events only; it must not decode rows or decide recovery.
Close the repository asynchronously on client shutdown; disconnect releases the
service but keeps the repository available for a later process-local partition.

- [x] **Step 5: Run parity checks and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.architecture.*' \
  --tests 'dev.tomewisp.client.MinecraftGuideHistoryScopeTest' \
  :fabric:compileJava :neoforge:compileJava
git add common/src/main/java/dev/tomewisp/client/MinecraftGuideHistoryScope.java \
  common/src/test/java/dev/tomewisp/client/MinecraftGuideHistoryScopeTest.java \
  common/src/test/java/dev/tomewisp/architecture \
  fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java \
  neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java
git commit -m "feat: partition guide history by connection"
```

Expected: architecture tests and both loader compilations pass.

Verification on 2026-07-18 compiled the resolver against the actual Minecraft
26.2 mappings, passed detached single-player/multiplayer normalization,
privacy, actor isolation, unavailable-state, and common loader-isolation tests,
then compiled both Fabric and NeoForge entrypoints. The full common suite passed
with 151 tests, 0 failures, 0 errors, and 1 existing skip. Both loaders use the
same common store/repository and asynchronously close it from their native
client-stopping lifecycle event.

### Task 7: Expose Diagnostics and Document Behavior

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/superpowers/specs/2026-07-18-phase-4-knowledge-persistence-rich-ui-design.md`

- [ ] **Step 1: Write failing UI projection tests**

Assert loading disables submission with a status row, unavailable history is a
nonfatal unsaved diagnostic, saving does not reorder timeline rows, interrupted
requests expose retry, and normal available state adds no transcript noise.

- [ ] **Step 2: Run and confirm missing projection**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*'
```

Expected: FAIL until persistence and interruption rows/actions exist.

- [ ] **Step 3: Add localized narrow UI states**

Project unframed status rows for `LOADING`, `SAVING`, and `UNAVAILABLE`. Keep
timeline chronology untouched. Disable submit while loading, keep it enabled
but visibly unsaved when unavailable, and use the existing retry action for
`INTERRUPTED`. Add English and Simplified Chinese translations for all text.

- [ ] **Step 4: Update documentation**

Document database location, hashed partition derivation, schema version,
normal-mode fields, interruption/manual retry, unsaved diagnostics, packaging
verification, and that developer payloads/history management arrive later in
Phase 4. Do not claim Phase 4 or graphical restart acceptance complete.

- [ ] **Step 5: Run checks and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*'
git diff --check
git add common/src/main/java/dev/tomewisp/guide/ui \
  common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java \
  common/src/main/resources/assets/tomewisp/lang \
  common/src/test/java/dev/tomewisp/guide/ui \
  README.md docs/development.md \
  docs/superpowers/specs/2026-07-18-phase-4-knowledge-persistence-rich-ui-design.md
git commit -m "feat: expose durable history state"
```

Expected: UI tests pass and documentation has no whitespace errors.

### Task 8: Full Phase 4B Verification

**Files:**
- Modify: `docs/superpowers/plans/2026-07-18-phase-4b-durable-history.md`
- Modify: `docs/isme/decisions/2026-07-18-006-durable-history-execution.md`
- Modify: `docs/isme/SKMB.md`

- [ ] **Step 1: Run focused durability and race suites**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.*' \
  --tests 'dev.tomewisp.guide.GuideServiceHistoryTest' \
  --tests 'dev.tomewisp.guide.GuideServiceTest' \
  --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.architecture.*'
```

Expected: all history, service, UI, and architecture tests pass.

- [ ] **Step 2: Run clean product gate and packaging proof**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
./scripts/verify-sqlite-packaging.sh
```

Expected: all common tests pass, both loaders build, and each production JAR
executes its packaged SQLite driver with required native coverage.

- [ ] **Step 3: Inspect exact artifacts and hygiene**

```bash
find fabric/build/libs neoforge/build/libs -maxdepth 1 -type f -name '*.jar' -print
git diff --check
git status --short --branch
```

Expected: no database, WAL, downloaded driver, run-directory, credential, or
generated artifact is staged.

- [ ] **Step 4: Record exact verification truth**

Append commands, test count, artifact names and SHA-256 values, SQLite version,
native matrix, and explicit unrun checks. Do not claim graphical restart proof;
that belongs to final Phase 4 modded-client acceptance.

- [ ] **Step 5: Update ISME commit references and commit**

Replace `pending` for SKMB-2026-07-18-006 with the decision-record commit that
introduced it, run any repository SKMB consistency check, then:

```bash
git add docs/superpowers/plans/2026-07-18-phase-4b-durable-history.md \
  docs/isme/SKMB.md \
  docs/isme/decisions/2026-07-18-006-durable-history-execution.md
git commit -m "docs: verify durable guide history"
```
