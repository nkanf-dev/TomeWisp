package dev.tomewisp.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.model.ModelUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteGuideHistoryStoreTest {
    private static final UUID ACTOR =
            UUID.fromString("028407d5-d694-4189-97dd-62e6cc137164");
    private static final UUID OTHER_ACTOR =
            UUID.fromString("efb0aa19-0378-442c-81dd-eb05468dfcad");
    private static final Instant RECOVERY_TIME = Instant.parse("2026-07-18T06:00:00Z");

    @TempDir Path temporary;

    @Test
    void keepsPartitionsIsolatedAcrossReplacement() {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition alpha = partition("alpha.example", "alpha", completed("alpha", "one"));
        GuideHistoryPartition beta = partition("beta.example", "beta", completed("beta", "two"));

        store.save(alpha);
        store.save(beta);
        GuideHistoryPartition replacement = partition(
                "alpha.example", "alpha", completed("alpha", "replacement"));
        store.save(replacement);

        assertEquals(replacement, store.load(alpha.scope()).partition().orElseThrow());
        assertEquals(beta, store.load(beta.scope()).partition().orElseThrow());
    }

    @Test
    void rebuildsRecognizedPreReleaseSchemasWithoutTouchingSiblingFiles() throws Exception {
        for (int oldVersion : List.of(1, 2, 3)) {
            Path versionDatabase = temporary.resolve("history-v" + oldVersion + ".sqlite3");
            SqliteGuideHistoryStore store = new SqliteGuideHistoryStore(
                    versionDatabase,
                    Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
                    new GuideHistoryCodec());
            GuideHistoryPartition original = partition(
                    "old-schema-" + oldVersion + ".example",
                    "main",
                    completed("main", "saved"));
            store.save(original);
            Path retained = temporary.resolve("retained-v" + oldVersion + ".txt");
            Files.writeString(retained, "retained");
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + versionDatabase);
                    var statement = connection.createStatement()) {
                statement.executeUpdate("update schema_metadata set schema_version = "
                        + oldVersion + " where singleton = 1");
            }

            assertTrue(store.load(original.scope()).partition().isEmpty());

            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + versionDatabase);
                    var statement = connection.createStatement()) {
                assertEquals(GuideHistoryPartition.SCHEMA_VERSION, queryInt(
                        statement,
                        "select schema_version from schema_metadata where singleton = 1"));
                assertEquals(0, queryInt(statement, "select count(*) from partitions"));
            }
            assertEquals("retained", Files.readString(retained));
        }
    }

    @Test
    void recognizedSchemaRebuildFailureRollsBackWithSpecificFailure() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition original =
                partition("old-rollback.example", "main", completed("main", "saved"));
        store.save(original);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 1 where singleton = 1");
        }
        SqliteGuideHistoryStore failing = new SqliteGuideHistoryStore(
                database(),
                Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
                new GuideHistoryCodec(),
                mutation -> {
                    if (mutation == SqliteGuideHistoryStore.Mutation.RESET) {
                        throw new java.sql.SQLException("injected rebuild failure");
                    }
                });

        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class, () -> failing.load(original.scope()));

        assertEquals("history_schema_rebuild_failed", failure.code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            assertEquals(1, queryInt(
                    statement,
                    "select schema_version from schema_metadata where singleton = 1"));
            assertEquals(1, queryInt(statement, "select count(*) from partitions"));
        }
    }

    @Test
    void roundTripsIndependentSessionAndCapturedRequestSelections() throws Exception {
        GuideRequestSnapshot clientRequest = completed(
                "client",
                "client answer",
                GuideTopology.CLIENT_LOCAL,
                GuideModelSelection.client("openrouter-claude"));
        GuideRequestSnapshot serverRequest = completed(
                "server",
                "server answer",
                GuideTopology.SERVER,
                GuideModelSelection.server());
        GuideHistoryPartition partition = new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                GuideHistoryScope.derive(
                        ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "selection.example"),
                "client",
                List.of(
                        new GuideSessionSnapshot(
                                "client", List.of(), List.of(clientRequest), List.of(),
                                GuideModelSelection.client("openrouter-claude")),
                        new GuideSessionSnapshot(
                                "server", List.of(), List.of(serverRequest), List.of(),
                                GuideModelSelection.server())),
                RECOVERY_TIME);
        SqliteGuideHistoryStore store = store();

        store.save(partition);

        assertEquals(partition, store.load(partition.scope()).partition().orElseThrow());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var result = connection.createStatement().executeQuery(
                        "select model_selection_json from sessions order by ordinal")) {
            assertTrue(result.next());
            assertEquals(
                    "{\"kind\":\"CLIENT\",\"profileId\":\"openrouter-claude\"}",
                    result.getString(1));
            assertTrue(result.next());
            assertEquals("{\"kind\":\"SERVER\"}", result.getString(1));
        }
    }

    @Test
    void roundTripsSuccessfulAndFailedCheckpointsWithinTheirPartition() throws Exception {
        GuideHistoryPartition base =
                partition("checkpoint.example", "main", completed("main", "saved"));
        ContextCheckpoint success = checkpoint(ContextCheckpoint.Status.SUCCEEDED);
        ContextCheckpoint failed = new ContextCheckpoint(
                UUID.randomUUID(), 0, 1, "b".repeat(64), "model-b", 1, 1,
                RECOVERY_TIME, ContextCheckpoint.Status.FAILED, null,
                "summary_malformed", "schema mismatch", 700);
        GuideSessionSnapshot session = base.sessions().getFirst();
        GuideHistoryPartition withCheckpoints = new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                base.scope(),
                base.selectedSession(),
                List.of(new GuideSessionSnapshot(
                        session.sessionId(), session.messages(), session.requests(),
                        List.of(success, failed), session.modelSelection())),
                base.updatedAt());
        SqliteGuideHistoryStore store = store();

        store.save(withCheckpoints);

        assertEquals(withCheckpoints, store.load(base.scope()).partition().orElseThrow());
        GuideHistoryPartition other =
                partition("other-checkpoint.example", "main", completed("main", "other"));
        store.save(other);
        assertTrue(store.load(other.scope()).partition().orElseThrow()
                .sessions().getFirst().checkpoints().isEmpty());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var result = connection.createStatement().executeQuery(
                        "select payload_json from compaction_checkpoints order by ordinal")) {
            assertTrue(result.next());
            String payload = result.getString(1);
            assertFalse(payload.contains("normalized"));
            assertFalse(payload.contains("reasoning"));
            assertFalse(payload.contains("authorization"));
        }
    }

    @Test
    void recoversActiveRequestAsInterruptedWithoutChangingTimelineOrder() {
        SqliteGuideHistoryStore store = store();
        GuideRequestSnapshot active = active("main", "partial");
        GuideHistoryPartition original = partition("active.example", "main", active);
        store.save(original);

        GuideHistoryPartition recovered = store.load(original.scope()).partition().orElseThrow();
        GuideRequestSnapshot request = recovered.sessions().getFirst().requests().getFirst();

        assertEquals(GuideRequestStatus.INTERRUPTED, request.status());
        assertEquals("request_interrupted", request.failure().code());
        assertEquals(RECOVERY_TIME, request.terminalAt());
        assertEquals(List.of(0), request.timeline().stream().map(GuideTimelineEntry::ordinal).toList());
        assertFalse(((GuideTimelineEntry.Assistant) request.timeline().getFirst()).streaming());
        assertEquals(request, store.load(original.scope()).partition().orElseThrow()
                .sessions().getFirst().requests().getFirst());
    }

    @Test
    void transactionFailureLeavesPreviousPartitionIntact() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition original = partition("rollback.example", "main", completed("main", "saved"));
        store.save(original);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.execute("""
                    create trigger reject_explode before insert on sessions
                    when new.session_id = 'explode'
                    begin select raise(abort, 'injected failure'); end
                    """);
        }

        GuideHistoryPartition rejected = partition(
                "rollback.example", "explode", completed("explode", "not saved"));
        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class, () -> store.save(rejected));

        assertEquals("history_write_failed", failure.code());
        assertEquals(original, store.load(original.scope()).partition().orElseThrow());
    }

    @Test
    void corruptPartitionIsDiagnosedWithoutBlockingAnotherPartition() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition corrupt = partition("corrupt.example", "main", completed("main", "bad"));
        GuideHistoryPartition healthy = partition("healthy.example", "main", completed("main", "good"));
        store.save(corrupt);
        store.save(healthy);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var update = connection.prepareStatement(
                        "update timeline_entries set payload_json = '{}' where scope_id = ?")) {
            update.setString(1, corrupt.scope().scopeId());
            update.executeUpdate();
        }

        GuideHistoryLoad load = store.load(corrupt.scope());

        assertTrue(load.partition().isEmpty());
        assertEquals("history_corrupt", load.diagnostics().getFirst().code());
        assertEquals(healthy, store.load(healthy.scope()).partition().orElseThrow());
    }

    @Test
    void futureSchemaFailsClosedWithoutMutation() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition saved = partition("future.example", "main", completed("main", "saved"));
        store.save(saved);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.executeUpdate("update schema_metadata set schema_version = 99 where singleton = 1");
        }

        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class, () -> store.load(saved.scope()));

        assertEquals("history_schema_unsupported", failure.code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var result = connection.createStatement().executeQuery(
                        "select schema_version from schema_metadata where singleton = 1")) {
            assertTrue(result.next());
            assertEquals(99, result.getInt(1));
        }
    }

    @Test
    void olderVersionWithForeignTableFailsClosedWithoutMutation() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition saved =
                partition("foreign-table.example", "main", completed("main", "saved"));
        store.save(saved);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.execute("create table unrelated_data(value text)");
            statement.execute("insert into unrelated_data(value) values ('retained')");
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 1 where singleton = 1");
        }

        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class, () -> store.load(saved.scope()));

        assertEquals("history_schema_unsupported", failure.code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            assertEquals(1, queryInt(
                    statement,
                    "select schema_version from schema_metadata where singleton = 1"));
            assertEquals(1, queryInt(statement, "select count(*) from unrelated_data"));
            assertEquals(1, queryInt(statement, "select count(*) from partitions"));
        }
    }

    @Test
    void partitionDeleteRemovesOnlyTheExactActorPartition() {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition current = partition(
                ACTOR, "current.example", "main", completed("main", "current"));
        GuideHistoryPartition sameActorOtherScope = partition(
                ACTOR, "other.example", "main", completed("main", "same actor"));
        GuideHistoryPartition otherActor = partition(
                OTHER_ACTOR, "current.example", "main", completed("main", "other actor"));
        store.save(current);
        store.save(sameActorOtherScope);
        store.save(otherActor);

        store.delete(GuideHistoryDeleteScope.partition(current.scope()));

        assertTrue(store.load(current.scope()).partition().isEmpty());
        assertEquals(
                sameActorOtherScope,
                store.load(sameActorOtherScope.scope()).partition().orElseThrow());
        assertEquals(otherActor, store.load(otherActor.scope()).partition().orElseThrow());
    }

    @Test
    void actorDeleteRemovesEveryMatchingPartitionAndNoOtherActor() {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition first = partition(
                ACTOR, "first.example", "main", completed("main", "first"));
        GuideHistoryPartition second = partition(
                ACTOR, "second.example", "main", completed("main", "second"));
        GuideHistoryPartition retained = partition(
                OTHER_ACTOR, "first.example", "main", completed("main", "retained"));
        store.save(first);
        store.save(second);
        store.save(retained);

        store.delete(GuideHistoryDeleteScope.actor(ACTOR));

        assertTrue(store.load(first.scope()).partition().isEmpty());
        assertTrue(store.load(second.scope()).partition().isEmpty());
        assertEquals(retained, store.load(retained.scope()).partition().orElseThrow());
    }

    @Test
    void deleteScopesRejectMissingActorAndPartitionIdentity() {
        assertThrows(NullPointerException.class, () -> GuideHistoryDeleteScope.actor(null));
        assertThrows(NullPointerException.class, () -> GuideHistoryDeleteScope.partition(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GuideHistoryScope(ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "not-a-scope"));
    }

    @Test
    void injectedDeleteFailureRollsBackEveryMatchingPartition() {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition first = partition(
                ACTOR, "rollback-first.example", "main", completed("main", "first"));
        GuideHistoryPartition second = partition(
                ACTOR, "rollback-second.example", "main", completed("main", "second"));
        store.save(first);
        store.save(second);
        SqliteGuideHistoryStore failing = new SqliteGuideHistoryStore(
                database(),
                Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
                new GuideHistoryCodec(),
                mutation -> {
                    if (mutation == SqliteGuideHistoryStore.Mutation.DELETE) {
                        throw new java.sql.SQLException("injected after delete");
                    }
                });

        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class,
                () -> failing.delete(GuideHistoryDeleteScope.actor(ACTOR)));

        assertEquals("history_delete_failed", failure.code());
        assertEquals(first, store.load(first.scope()).partition().orElseThrow());
        assertEquals(second, store.load(second.scope()).partition().orElseThrow());
    }

    @Test
    void explicitResetReplacesUnsupportedSchemaWithoutDeletingSiblingFiles() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition original = partition(
                ACTOR, "reset.example", "main", completed("main", "saved"));
        store.save(original);
        Path retainedTrace = temporary.resolve("developer-trace.json");
        Files.writeString(retainedTrace, "retained");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 999 where singleton = 1");
        }

        store.resetDatabase();

        assertTrue(store.load(original.scope()).partition().isEmpty());
        assertEquals("retained", Files.readString(retainedTrace));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            assertEquals(GuideHistoryPartition.SCHEMA_VERSION, queryInt(
                    statement,
                    "select schema_version from schema_metadata where singleton = 1"));
            assertEquals(0, queryInt(statement, "select count(*) from partitions"));
        }
    }

    @Test
    void injectedResetFailureRestoresUnsupportedSchemaAndEveryPriorRow() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition original = partition(
                ACTOR, "reset-rollback.example", "main", completed("main", "saved"));
        store.save(original);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 999 where singleton = 1");
        }
        SqliteGuideHistoryStore failing = new SqliteGuideHistoryStore(
                database(),
                Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
                new GuideHistoryCodec(),
                mutation -> {
                    if (mutation == SqliteGuideHistoryStore.Mutation.RESET) {
                        throw new java.sql.SQLException("injected after schema recreation");
                    }
                });

        GuideHistoryException failure = assertThrows(
                GuideHistoryException.class, failing::resetDatabase);

        assertEquals("history_delete_failed", failure.code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            assertEquals(999, queryInt(
                    statement,
                    "select schema_version from schema_metadata where singleton = 1"));
            assertEquals(1, queryInt(statement, "select count(*) from partitions"));
            assertEquals(2, queryInt(statement, "select count(*) from messages"));
        }
    }

    private SqliteGuideHistoryStore store() {
        return new SqliteGuideHistoryStore(
                database(),
                Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
                new GuideHistoryCodec());
    }

    private Path database() {
        return temporary.resolve("history.sqlite3");
    }

    private static int queryInt(java.sql.Statement statement, String query) throws Exception {
        try (var result = statement.executeQuery(query)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static GuideHistoryPartition partition(
            String server, String sessionId, GuideRequestSnapshot request) {
        return partition(ACTOR, server, sessionId, request);
    }

    private static GuideHistoryPartition partition(
            UUID actor,
            String server,
            String sessionId,
            GuideRequestSnapshot request) {
        GuideHistoryScope scope = GuideHistoryScope.derive(
                actor, GuideHistoryScope.Kind.MULTIPLAYER, server);
        List<GuideMessage> messages = request.status() == GuideRequestStatus.COMPLETED
                ? List.of(
                        new GuideMessage(request.requestId(), GuideMessage.Role.USER,
                                request.userMessage(), request.createdAt()),
                        new GuideMessage(request.requestId(), GuideMessage.Role.ASSISTANT,
                                request.assistantText(), request.terminalAt()))
                : List.of(new GuideMessage(
                        request.requestId(), GuideMessage.Role.USER,
                        request.userMessage(), request.createdAt()));
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                scope,
                sessionId,
                List.of(new GuideSessionSnapshot(sessionId, messages, List.of(request))),
                request.updatedAt());
    }

    private static GuideRequestSnapshot completed(String sessionId, String answer) {
        return completed(
                sessionId,
                answer,
                GuideTopology.CLIENT_LOCAL,
                GuideModelSelection.client("default"));
    }

    private static GuideRequestSnapshot completed(
            String sessionId,
            String answer,
            GuideTopology topology,
            GuideModelSelection modelSelection) {
        UUID requestId = UUID.nameUUIDFromBytes((sessionId + answer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant terminal = Instant.parse("2026-07-18T05:00:00Z");
        return new GuideRequestSnapshot(
                requestId,
                sessionId,
                topology,
                "question " + answer,
                List.of(
                        new GuideTimelineEntry.Assistant(0, "checking " + answer, false, List.of()),
                        new GuideTimelineEntry.Assistant(1, answer, false, List.of())),
                GuideRequestStatus.COMPLETED,
                List.of(),
                new ModelUsage(10, 2, 0),
                null,
                null,
                terminal.minusSeconds(2),
                terminal,
                terminal,
                modelSelection);
    }

    private static GuideRequestSnapshot active(String sessionId, String partial) {
        Instant updated = Instant.parse("2026-07-18T05:30:00Z");
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                UUID.nameUUIDFromBytes(partial.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sessionId,
                GuideTopology.CLIENT_LOCAL,
                "question active",
                List.of(new GuideTimelineEntry.Assistant(0, partial, true, List.of())),
                GuideRequestStatus.MODEL_WAIT,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                updated.minusSeconds(1),
                updated,
                null);
        assertNotNull(request);
        return request;
    }

    private static ContextCheckpoint checkpoint(ContextCheckpoint.Status status) {
        if (status != ContextCheckpoint.Status.SUCCEEDED) {
            throw new IllegalArgumentException("success helper only");
        }
        return new ContextCheckpoint(
                UUID.randomUUID(), 0, 1, "a".repeat(64), "model-a", 1, 1,
                RECOVERY_TIME, status,
                "{\"goals\":[],\"preferences\":[],\"completedTopics\":[],"
                        + "\"currentTasks\":[],\"decisions\":[],"
                        + "\"unresolvedQuestions\":[],\"evidenceReferences\":[]}",
                null, null, 600);
    }
}
