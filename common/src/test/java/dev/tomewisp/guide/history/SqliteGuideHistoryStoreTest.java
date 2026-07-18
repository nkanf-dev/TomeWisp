package dev.tomewisp.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.model.ModelUsage;
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
    private static final Instant RECOVERY_TIME = Instant.parse("2026-07-18T06:00:00Z");

    @TempDir Path temporary;

    @Test
    void migratesAndKeepsPartitionsIsolatedAcrossReplacement() {
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
    void migratesSchemaOneInPlaceWithoutChangingMessagesOrTimeline() throws Exception {
        SqliteGuideHistoryStore store = store();
        GuideHistoryPartition original =
                partition("migration.example", "main", completed("main", "saved"));
        store.save(original);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            statement.execute("drop table compaction_checkpoints");
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 1 where singleton = 1");
        }

        GuideHistoryPartition loaded = store.load(original.scope()).partition().orElseThrow();

        assertEquals(original, loaded);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
                var statement = connection.createStatement()) {
            assertEquals(2, queryInt(
                    statement,
                    "select schema_version from schema_metadata where singleton = 1"));
            assertEquals(2, queryInt(statement, "select count(*) from messages"));
            assertEquals(2, queryInt(statement, "select count(*) from timeline_entries"));
            assertEquals(0, queryInt(
                    statement, "select count(*) from compaction_checkpoints"));
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
                base.modelMode(),
                List.of(new GuideSessionSnapshot(
                        session.sessionId(), session.messages(), session.requests(),
                        List.of(success, failed))),
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
        GuideHistoryScope scope = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, server);
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
                GuideModelMode.CLIENT,
                List.of(new GuideSessionSnapshot(sessionId, messages, List.of(request))),
                request.updatedAt());
    }

    private static GuideRequestSnapshot completed(String sessionId, String answer) {
        UUID requestId = UUID.nameUUIDFromBytes((sessionId + answer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant terminal = Instant.parse("2026-07-18T05:00:00Z");
        return new GuideRequestSnapshot(
                requestId,
                sessionId,
                GuideTopology.CLIENT_LOCAL,
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
                terminal);
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
