package dev.tomewisp.guide.history;

import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.model.ModelUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC store used only behind the asynchronous history repository. */
public final class SqliteGuideHistoryStore implements GuideHistoryStore {
    private static final int SCHEMA_VERSION = 2;

    private final Path database;
    private final Clock clock;
    private final GuideHistoryCodec codec;

    public SqliteGuideHistoryStore(Path database, Clock clock, GuideHistoryCodec codec) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public GuideHistoryLoad load(GuideHistoryScope scope) {
        Objects.requireNonNull(scope, "scope");
        GuideHistoryPartition loaded;
        try (Connection connection = open()) {
            try {
                loaded = readPartition(connection, scope);
            } catch (IllegalArgumentException malformed) {
                return new GuideHistoryLoad(
                        Optional.empty(),
                        List.of(new GuideFailure(
                                "history_corrupt",
                                "Durable history for this partition is malformed")));
            }
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_load_failed", "Unable to load durable guide history", failure);
        }
        if (loaded == null) {
            return GuideHistoryLoad.empty();
        }
        GuideHistoryPartition recovered = recoverInterrupted(loaded);
        if (recovered != loaded) {
            save(recovered);
        }
        return new GuideHistoryLoad(Optional.of(recovered), List.of());
    }

    @Override
    public void save(GuideHistoryPartition partition) {
        Objects.requireNonNull(partition, "partition");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deletePartition(connection, partition.scope().scopeId());
                insertPartition(connection, partition);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof GuideHistoryException historyFailure) {
                    throw historyFailure;
                }
                throw new GuideHistoryException(
                        "history_write_failed", "Unable to save durable guide history", failure);
            }
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_write_failed", "Unable to save durable guide history", failure);
        }
    }

    private Connection open() {
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            try {
                configure(connection);
                ensureSchema(connection);
                return connection;
            } catch (SQLException | RuntimeException failure) {
                connection.close();
                throw failure;
            }
        } catch (IOException | SQLException failure) {
            throw new GuideHistoryException(
                    "history_open_failed", "Unable to open the guide history database", failure);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys=on");
            statement.execute("pragma journal_mode=wal");
            statement.execute("pragma synchronous=full");
        }
    }

    private static void ensureSchema(Connection connection) throws SQLException {
        if (!tableExists(connection, "schema_metadata")) {
            createSchema(connection);
            return;
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select schema_version from schema_metadata where singleton = 1")) {
            if (!result.next()) {
                throw new GuideHistoryException(
                        "history_schema_unsupported", "Guide history schema metadata is missing");
            }
            int version = result.getInt(1);
            if (version == 1) {
                migrateV1ToV2(connection);
            } else if (version != SCHEMA_VERSION) {
                throw new GuideHistoryException(
                        "history_schema_unsupported",
                        "Unsupported guide history schema version " + version);
            }
        }
    }

    private static void migrateV1ToV2(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            createCheckpointTable(statement);
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 2 where singleton = 1 and schema_version = 1");
            connection.commit();
        } catch (SQLException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select 1 from sqlite_master where type = 'table' and name = ?")) {
            query.setString(1, table);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table schema_metadata(
                        singleton integer primary key check(singleton = 1),
                        schema_version integer not null
                    )
                    """);
            statement.execute("""
                    create table partitions(
                        scope_id text primary key,
                        actor_id text not null,
                        connection_kind text not null,
                        selected_session text not null,
                        model_mode text not null,
                        capture_mode text not null check(capture_mode = 'NORMAL'),
                        updated_at text not null
                    )
                    """);
            statement.execute("""
                    create table sessions(
                        scope_id text not null,
                        session_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        primary key(scope_id, session_id),
                        unique(scope_id, ordinal),
                        foreign key(scope_id) references partitions(scope_id) on delete cascade
                    )
                    """);
            statement.execute("""
                    create table requests(
                        scope_id text not null,
                        session_id text not null,
                        request_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        topology text not null,
                        user_message text not null,
                        status text not null,
                        input_tokens integer not null check(input_tokens >= 0),
                        output_tokens integer not null check(output_tokens >= 0),
                        cache_read_tokens integer not null check(cache_read_tokens >= 0),
                        retry_after_millis integer,
                        failure_code text,
                        failure_message text,
                        created_at text not null,
                        updated_at text not null,
                        terminal_at text,
                        primary key(scope_id, request_id),
                        unique(scope_id, session_id, ordinal),
                        foreign key(scope_id, session_id)
                            references sessions(scope_id, session_id) on delete cascade
                    )
                    """);
            statement.execute("""
                    create table messages(
                        scope_id text not null,
                        session_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        request_id text not null,
                        role text not null,
                        message_text text not null,
                        created_at text not null,
                        primary key(scope_id, session_id, ordinal),
                        foreign key(scope_id, session_id)
                            references sessions(scope_id, session_id) on delete cascade,
                        foreign key(scope_id, request_id)
                            references requests(scope_id, request_id) on delete cascade
                    )
                    """);
            statement.execute("""
                    create table timeline_entries(
                        scope_id text not null,
                        request_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        payload_json text not null,
                        primary key(scope_id, request_id, ordinal),
                        foreign key(scope_id, request_id)
                            references requests(scope_id, request_id) on delete cascade
                    )
                    """);
            statement.execute("""
                    create table request_sources(
                        scope_id text not null,
                        request_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        payload_json text not null,
                        primary key(scope_id, request_id, ordinal),
                        foreign key(scope_id, request_id)
                            references requests(scope_id, request_id) on delete cascade
                    )
                    """);
            statement.execute("create index sessions_updated_lookup on sessions(scope_id, ordinal)");
            statement.execute("create index requests_order_lookup on requests(scope_id, session_id, ordinal)");
            statement.execute("create index timeline_order_lookup on timeline_entries(scope_id, request_id, ordinal)");
            createCheckpointTable(statement);
            statement.execute("insert into schema_metadata(singleton, schema_version) values (1, 2)");
            connection.commit();
        } catch (SQLException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void createCheckpointTable(Statement statement) throws SQLException {
        statement.execute("""
                create table if not exists compaction_checkpoints(
                    scope_id text not null,
                    session_id text not null,
                    ordinal integer not null check(ordinal >= 0),
                    checkpoint_id text not null,
                    payload_json text not null,
                    primary key(scope_id, session_id, ordinal),
                    unique(scope_id, checkpoint_id),
                    foreign key(scope_id, session_id)
                        references sessions(scope_id, session_id) on delete cascade
                )
                """);
        statement.execute("""
                create index if not exists checkpoint_order_lookup
                on compaction_checkpoints(scope_id, session_id, ordinal)
                """);
    }

    private static void deletePartition(Connection connection, String scopeId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from partitions where scope_id = ?")) {
            delete.setString(1, scopeId);
            delete.executeUpdate();
        }
    }

    private void insertPartition(Connection connection, GuideHistoryPartition partition)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into partitions(
                    scope_id, actor_id, connection_kind, selected_session,
                    model_mode, capture_mode, updated_at)
                values (?, ?, ?, ?, ?, 'NORMAL', ?)
                """)) {
            insert.setString(1, partition.scope().scopeId());
            insert.setString(2, partition.scope().actorId().toString());
            insert.setString(3, partition.scope().kind().name());
            insert.setString(4, partition.selectedSession());
            insert.setString(5, partition.modelMode().name());
            insert.setString(6, partition.updatedAt().toString());
            insert.executeUpdate();
        }
        for (int sessionOrdinal = 0; sessionOrdinal < partition.sessions().size(); sessionOrdinal++) {
            GuideSessionSnapshot session = partition.sessions().get(sessionOrdinal);
            insertSession(connection, partition.scope().scopeId(), session, sessionOrdinal);
            for (int requestOrdinal = 0; requestOrdinal < session.requests().size(); requestOrdinal++) {
                insertRequest(
                        connection,
                        partition.scope().scopeId(),
                        session.sessionId(),
                        session.requests().get(requestOrdinal),
                        requestOrdinal);
            }
            for (int messageOrdinal = 0; messageOrdinal < session.messages().size(); messageOrdinal++) {
                insertMessage(
                        connection,
                        partition.scope().scopeId(),
                        session.sessionId(),
                        session.messages().get(messageOrdinal),
                        messageOrdinal);
            }
            for (int checkpointOrdinal = 0;
                    checkpointOrdinal < session.checkpoints().size();
                    checkpointOrdinal++) {
                insertCheckpoint(
                        connection,
                        partition.scope().scopeId(),
                        session.sessionId(),
                        session.checkpoints().get(checkpointOrdinal),
                        checkpointOrdinal);
            }
        }
    }

    private void insertCheckpoint(
            Connection connection,
            String scopeId,
            String sessionId,
            ContextCheckpoint checkpoint,
            int ordinal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into compaction_checkpoints(
                    scope_id, session_id, ordinal, checkpoint_id, payload_json)
                values (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, scopeId);
            insert.setString(2, sessionId);
            insert.setInt(3, ordinal);
            insert.setString(4, checkpoint.checkpointId().toString());
            insert.setString(5, codec.encodeCheckpoint(checkpoint));
            insert.executeUpdate();
        }
    }

    private static void insertSession(
            Connection connection,
            String scopeId,
            GuideSessionSnapshot session,
            int ordinal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into sessions(scope_id, session_id, ordinal) values (?, ?, ?)")) {
            insert.setString(1, scopeId);
            insert.setString(2, session.sessionId());
            insert.setInt(3, ordinal);
            insert.executeUpdate();
        }
    }

    private void insertRequest(
            Connection connection,
            String scopeId,
            String sessionId,
            GuideRequestSnapshot request,
            int ordinal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into requests(
                    scope_id, session_id, request_id, ordinal, topology, user_message,
                    status, input_tokens, output_tokens, cache_read_tokens,
                    retry_after_millis, failure_code, failure_message,
                    created_at, updated_at, terminal_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, scopeId);
            insert.setString(2, sessionId);
            insert.setString(3, request.requestId().toString());
            insert.setInt(4, ordinal);
            insert.setString(5, request.topology().name());
            insert.setString(6, request.userMessage());
            insert.setString(7, request.status().name());
            insert.setLong(8, request.usage().inputTokens());
            insert.setLong(9, request.usage().outputTokens());
            insert.setLong(10, request.usage().cacheReadTokens());
            nullableLong(insert, 11, request.retryAfterMillis());
            insert.setString(12, request.failure() == null ? null : request.failure().code());
            insert.setString(13, request.failure() == null ? null : request.failure().message());
            insert.setString(14, request.createdAt().toString());
            insert.setString(15, request.updatedAt().toString());
            insert.setString(16, request.terminalAt() == null ? null : request.terminalAt().toString());
            insert.executeUpdate();
        }
        for (GuideTimelineEntry entry : request.timeline()) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into timeline_entries(scope_id, request_id, ordinal, payload_json)
                    values (?, ?, ?, ?)
                    """)) {
                insert.setString(1, scopeId);
                insert.setString(2, request.requestId().toString());
                insert.setInt(3, entry.ordinal());
                insert.setString(4, codec.encodeEntry(entry));
                insert.executeUpdate();
            }
        }
        for (int sourceOrdinal = 0; sourceOrdinal < request.sources().size(); sourceOrdinal++) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into request_sources(scope_id, request_id, ordinal, payload_json)
                    values (?, ?, ?, ?)
                    """)) {
                insert.setString(1, scopeId);
                insert.setString(2, request.requestId().toString());
                insert.setInt(3, sourceOrdinal);
                insert.setString(4, codec.encodeSources(List.of(request.sources().get(sourceOrdinal))));
                insert.executeUpdate();
            }
        }
    }

    private static void insertMessage(
            Connection connection,
            String scopeId,
            String sessionId,
            GuideMessage message,
            int ordinal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into messages(
                    scope_id, session_id, ordinal, request_id, role, message_text, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, scopeId);
            insert.setString(2, sessionId);
            insert.setInt(3, ordinal);
            insert.setString(4, message.requestId().toString());
            insert.setString(5, message.role().name());
            insert.setString(6, message.text());
            insert.setString(7, message.createdAt().toString());
            insert.executeUpdate();
        }
    }

    private GuideHistoryPartition readPartition(Connection connection, GuideHistoryScope scope)
            throws SQLException {
        PartitionHeader header = readHeader(connection, scope);
        if (header == null) {
            return null;
        }
        LinkedHashMap<String, SessionBuilder> sessions = readSessions(connection, scope.scopeId());
        readRequests(connection, scope.scopeId(), sessions);
        readMessages(connection, scope.scopeId(), sessions);
        readCheckpoints(connection, scope.scopeId(), sessions);
        List<GuideSessionSnapshot> snapshots = sessions.values().stream()
                .map(SessionBuilder::snapshot)
                .toList();
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                scope,
                header.selectedSession,
                header.modelMode,
                snapshots,
                header.updatedAt);
    }

    private static PartitionHeader readHeader(Connection connection, GuideHistoryScope scope)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select actor_id, connection_kind, selected_session, model_mode, capture_mode, updated_at
                from partitions where scope_id = ?
                """)) {
            query.setString(1, scope.scopeId());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                if (!scope.actorId().equals(UUID.fromString(result.getString("actor_id")))
                        || scope.kind() != GuideHistoryScope.Kind.valueOf(
                                result.getString("connection_kind"))) {
                    throw new IllegalArgumentException("durable history scope metadata does not match");
                }
                if (!"NORMAL".equals(result.getString("capture_mode"))) {
                    throw new IllegalArgumentException("unsupported durable capture mode");
                }
                return new PartitionHeader(
                        result.getString("selected_session"),
                        GuideModelMode.valueOf(result.getString("model_mode")),
                        Instant.parse(result.getString("updated_at")));
            }
        }
    }

    private static LinkedHashMap<String, SessionBuilder> readSessions(
            Connection connection, String scopeId) throws SQLException {
        LinkedHashMap<String, SessionBuilder> sessions = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id from sessions where scope_id = ? order by ordinal
                """)) {
            query.setString(1, scopeId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String sessionId = result.getString(1);
                    if (sessions.put(sessionId, new SessionBuilder(sessionId)) != null) {
                        throw new IllegalArgumentException("duplicate durable session");
                    }
                }
            }
        }
        return sessions;
    }

    private void readRequests(
            Connection connection,
            String scopeId,
            Map<String, SessionBuilder> sessions) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id, request_id, topology, user_message, status,
                       input_tokens, output_tokens, cache_read_tokens, retry_after_millis,
                       failure_code, failure_message, created_at, updated_at, terminal_at
                from requests where scope_id = ? order by session_id, ordinal
                """)) {
            query.setString(1, scopeId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String sessionId = result.getString("session_id");
                    SessionBuilder session = sessions.get(sessionId);
                    if (session == null) {
                        throw new IllegalArgumentException("durable request has no session");
                    }
                    UUID requestId = UUID.fromString(result.getString("request_id"));
                    GuideFailure failure = result.getString("failure_code") == null
                            ? null
                            : new GuideFailure(
                                    result.getString("failure_code"),
                                    required(result.getString("failure_message"), "failure message"));
                    session.requests.add(new GuideRequestSnapshot(
                            requestId,
                            sessionId,
                            GuideTopology.valueOf(result.getString("topology")),
                            result.getString("user_message"),
                            readTimeline(connection, scopeId, requestId),
                            GuideRequestStatus.valueOf(result.getString("status")),
                            readSources(connection, scopeId, requestId),
                            new ModelUsage(
                                    result.getLong("input_tokens"),
                                    result.getLong("output_tokens"),
                                    result.getLong("cache_read_tokens")),
                            nullableLong(result, "retry_after_millis"),
                            failure,
                            Instant.parse(result.getString("created_at")),
                            Instant.parse(result.getString("updated_at")),
                            nullableInstant(result.getString("terminal_at"))));
                }
            }
        }
    }

    private List<GuideTimelineEntry> readTimeline(
            Connection connection, String scopeId, UUID requestId) throws SQLException {
        List<GuideTimelineEntry> timeline = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                select ordinal, payload_json from timeline_entries
                where scope_id = ? and request_id = ? order by ordinal
                """)) {
            query.setString(1, scopeId);
            query.setString(2, requestId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    GuideTimelineEntry decoded = codec.decodeEntry(result.getString("payload_json"));
                    if (decoded.ordinal() != result.getInt("ordinal")) {
                        throw new IllegalArgumentException("durable timeline row identity does not match");
                    }
                    timeline.add(decoded);
                }
            }
        }
        for (int ordinal = 0; ordinal < timeline.size(); ordinal++) {
            if (timeline.get(ordinal).ordinal() != ordinal) {
                throw new IllegalArgumentException("durable timeline ordinals are not contiguous");
            }
        }
        return List.copyOf(timeline);
    }

    private List<GuideSource> readSources(
            Connection connection, String scopeId, UUID requestId) throws SQLException {
        List<GuideSource> sources = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                select ordinal, payload_json from request_sources
                where scope_id = ? and request_id = ? order by ordinal
                """)) {
            query.setString(1, scopeId);
            query.setString(2, requestId.toString());
            try (ResultSet result = query.executeQuery()) {
                int expected = 0;
                while (result.next()) {
                    if (result.getInt("ordinal") != expected++) {
                        throw new IllegalArgumentException("durable source ordinals are not contiguous");
                    }
                    List<GuideSource> decoded = codec.decodeSources(result.getString("payload_json"));
                    if (decoded.size() != 1) {
                        throw new IllegalArgumentException("durable source row must contain one source");
                    }
                    sources.add(decoded.getFirst());
                }
            }
        }
        return List.copyOf(sources);
    }

    private static void readMessages(
            Connection connection,
            String scopeId,
            Map<String, SessionBuilder> sessions) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id, request_id, role, message_text, created_at
                from messages where scope_id = ? order by session_id, ordinal
                """)) {
            query.setString(1, scopeId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    SessionBuilder session = sessions.get(result.getString("session_id"));
                    if (session == null) {
                        throw new IllegalArgumentException("durable message has no session");
                    }
                    session.messages.add(new GuideMessage(
                            UUID.fromString(result.getString("request_id")),
                            GuideMessage.Role.valueOf(result.getString("role")),
                            result.getString("message_text"),
                            Instant.parse(result.getString("created_at"))));
                }
            }
        }
    }

    private void readCheckpoints(
            Connection connection,
            String scopeId,
            Map<String, SessionBuilder> sessions) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id, ordinal, checkpoint_id, payload_json
                from compaction_checkpoints where scope_id = ? order by session_id, ordinal
                """)) {
            query.setString(1, scopeId);
            try (ResultSet result = query.executeQuery()) {
                Map<String, Integer> expected = new java.util.HashMap<>();
                while (result.next()) {
                    String sessionId = result.getString("session_id");
                    SessionBuilder session = sessions.get(sessionId);
                    if (session == null) {
                        throw new IllegalArgumentException("durable checkpoint has no session");
                    }
                    int ordinal = result.getInt("ordinal");
                    if (ordinal != expected.getOrDefault(sessionId, 0)) {
                        throw new IllegalArgumentException(
                                "durable checkpoint ordinals are not contiguous");
                    }
                    expected.put(sessionId, ordinal + 1);
                    ContextCheckpoint checkpoint =
                            codec.decodeCheckpoint(result.getString("payload_json"));
                    if (!checkpoint.checkpointId().toString().equals(
                            result.getString("checkpoint_id"))) {
                        throw new IllegalArgumentException(
                                "durable checkpoint row identity does not match");
                    }
                    session.checkpoints.add(checkpoint);
                }
            }
        }
    }

    private GuideHistoryPartition recoverInterrupted(GuideHistoryPartition partition) {
        Instant recoveredAt = clock.instant();
        boolean changed = false;
        List<GuideSessionSnapshot> sessions = new ArrayList<>();
        for (GuideSessionSnapshot session : partition.sessions()) {
            List<GuideRequestSnapshot> requests = new ArrayList<>();
            for (GuideRequestSnapshot request : session.requests()) {
                if (request.terminal()) {
                    requests.add(request);
                    continue;
                }
                changed = true;
                List<GuideTimelineEntry> timeline = request.timeline().stream()
                        .map(entry -> entry instanceof GuideTimelineEntry.Assistant assistant
                                && assistant.streaming()
                                        ? new GuideTimelineEntry.Assistant(
                                                assistant.ordinal(),
                                                assistant.text(),
                                                false,
                                                assistant.sources())
                                        : entry)
                        .toList();
                requests.add(new GuideRequestSnapshot(
                        request.requestId(),
                        request.sessionId(),
                        request.topology(),
                        request.userMessage(),
                        timeline,
                        GuideRequestStatus.INTERRUPTED,
                        request.sources(),
                        request.usage(),
                        null,
                        new GuideFailure(
                                "request_interrupted",
                                "The previous client process ended before this request completed"),
                        request.createdAt(),
                        recoveredAt,
                        recoveredAt));
            }
            sessions.add(new GuideSessionSnapshot(
                    session.sessionId(), session.messages(), requests, session.checkpoints()));
        }
        if (!changed) {
            return partition;
        }
        return new GuideHistoryPartition(
                partition.schemaVersion(),
                partition.scope(),
                partition.selectedSession(),
                partition.modelMode(),
                sessions,
                recoveredAt);
    }

    private static Long nullableLong(ResultSet result, String field) throws SQLException {
        long value = result.getLong(field);
        return result.wasNull() ? null : value;
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Instant nullableInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String required(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return value;
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record PartitionHeader(
            String selectedSession,
            GuideModelMode modelMode,
            Instant updatedAt) {}

    private static final class SessionBuilder {
        private final String id;
        private final List<GuideMessage> messages = new ArrayList<>();
        private final List<GuideRequestSnapshot> requests = new ArrayList<>();
        private final List<ContextCheckpoint> checkpoints = new ArrayList<>();

        private SessionBuilder(String id) {
            this.id = id;
        }

        private GuideSessionSnapshot snapshot() {
            return new GuideSessionSnapshot(id, messages, requests, checkpoints);
        }
    }
}
