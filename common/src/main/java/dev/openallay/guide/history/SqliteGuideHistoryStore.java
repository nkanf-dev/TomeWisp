package dev.openallay.guide.history;

import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.agent.context.ContextSourceHash;
import dev.openallay.guide.GuideFailure;
import dev.openallay.guide.GuideMessage;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideTopology;
import dev.openallay.guide.GuideToolMessageCodec;
import dev.openallay.model.ModelUsage;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.agent.context.Utf8ContextTokenEstimator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
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
import java.util.Set;
import java.util.UUID;

/** JDBC store used only behind the asynchronous history repository. */
public final class SqliteGuideHistoryStore implements GuideHistoryStore {
    private static final int SCHEMA_VERSION = GuideHistoryPartition.SCHEMA_VERSION;
    private static final Map<Integer, Map<String, List<ColumnSignature>>>
            HISTORY_SCHEMA_SIGNATURES = historySchemaSignatures();

    private final Path database;
    private final Clock clock;
    private final GuideHistoryCodec codec;
    private final FailureInjector failureInjector;

    public SqliteGuideHistoryStore(Path database, Clock clock, GuideHistoryCodec codec) {
        this(database, clock, codec, ignored -> {});
    }

    SqliteGuideHistoryStore(
            Path database,
            Clock clock,
            GuideHistoryCodec codec,
            FailureInjector failureInjector) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
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

    @Override
    public Optional<GuideHistoryMetadata> metadata(GuideHistoryScope scope) {
        Objects.requireNonNull(scope, "scope");
        try (Connection connection = open()) {
            recoverInterruptedRows(connection, scope);
            PartitionHeader header = readHeader(connection, scope);
            if (header == null) {
                return Optional.empty();
            }
            List<GuideHistoryMetadata.Session> sessions = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    select s.session_id, s.ordinal, s.model_selection_json,
                           count(r.request_id) as request_count,
                           min(r.sequence) as first_sequence,
                           max(r.sequence) as last_sequence
                    from sessions s
                    left join requests r
                      on r.scope_id = s.scope_id and r.session_id = s.session_id
                    where s.scope_id = ?
                    group by s.session_id, s.ordinal, s.model_selection_json
                    order by s.ordinal
                    """)) {
                query.setString(1, scope.scopeId());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        long count = result.getLong("request_count");
                        Long firstSequence = nullableLong(result, "first_sequence");
                        Long lastSequence = nullableLong(result, "last_sequence");
                        sessions.add(new GuideHistoryMetadata.Session(
                                result.getString("session_id"),
                                result.getInt("ordinal"),
                                codec.decodeModelSelection(result.getString("model_selection_json")),
                                count,
                                firstSequence == null ? null : cursor(
                                        connection, scope.scopeId(),
                                        result.getString("session_id"), firstSequence),
                                lastSequence == null ? null : cursor(
                                        connection, scope.scopeId(),
                                        result.getString("session_id"), lastSequence)));
                    }
                }
            }
            return Optional.of(new GuideHistoryMetadata(
                    scope, header.selectedSession, sessions, header.updatedAt));
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_metadata_failed", "Unable to load guide history metadata", failure);
        } catch (IllegalArgumentException malformed) {
            throw new GuideHistoryException(
                    "history_corrupt", "Guide history metadata is malformed", malformed);
        }
    }

    @Override
    public GuideHistoryPage page(GuideHistoryPageRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = open()) {
            List<SequencedRequest> loaded = readPage(connection, request);
            List<GuideRequestSnapshot> snapshots = loaded.stream()
                    .map(SequencedRequest::request).toList();
            GuideHistoryCursor first = loaded.isEmpty() ? null : loaded.getFirst().cursor();
            GuideHistoryCursor last = loaded.isEmpty() ? null : loaded.getLast().cursor();
            boolean hasEarlier = first != null && requestExists(
                    connection, request.scope().scopeId(), request.sessionId(),
                    "sequence < ?", first.sequence());
            boolean hasLater = last != null && requestExists(
                    connection, request.scope().scopeId(), request.sessionId(),
                    "sequence > ?", last.sequence());
            return new GuideHistoryPage(
                    request.sessionId(), snapshots, first, last, hasEarlier, hasLater);
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_page_failed", "Unable to load a guide history page", failure);
        } catch (IllegalArgumentException malformed) {
            throw new GuideHistoryException(
                    "history_corrupt", "A guide history page is malformed", malformed);
        }
    }

    @Override
    public GuideHistoryContextSeed context(GuideHistoryContextRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = open()) {
            Utf8ContextTokenEstimator estimator = new Utf8ContextTokenEstimator();
            List<List<ModelMessage>> acceptedNewest = new ArrayList<>();
            int estimated = 0;
            GuideHistoryCursor oldest = null;
            String sql = requestColumns()
                    + " from requests where scope_id = ? and session_id = ?"
                    + " and terminal_at is not null order by sequence desc";
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, request.scope().scopeId());
                query.setString(2, request.sessionId());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        SequencedRequest value = readRequestRow(
                                connection, request.scope().scopeId(), result);
                        List<ModelMessage> requestMessages = contextMessages(value.request());
                        List<ModelMessage> candidate = new ArrayList<>();
                        for (int index = acceptedNewest.size() - 1; index >= 0; index--) {
                            candidate.addAll(acceptedNewest.get(index));
                        }
                        candidate.addAll(0, requestMessages);
                        int candidateEstimate = estimator.estimate(
                                "history", candidate, List.of());
                        if (candidateEstimate > request.availableHistoryTokens()) {
                            break;
                        }
                        acceptedNewest.add(requestMessages);
                        estimated = candidateEstimate;
                        oldest = value.cursor();
                    }
                }
            }
            List<ModelMessage> messages = new ArrayList<>();
            for (int index = acceptedNewest.size() - 1; index >= 0; index--) {
                messages.addAll(acceptedNewest.get(index));
            }
            List<ContextCheckpoint> checkpoints = List.of();
            if (oldest != null && oldest.sequence() == 0) {
                List<ContextCheckpoint> candidate = readApplicableCheckpoint(
                        connection, request.scope().scopeId(), request.sessionId(),
                        request.modelIdentifier());
                if (!candidate.isEmpty()) {
                    ContextCheckpoint checkpoint = candidate.getFirst();
                    if (checkpoint.sourceFromIndex() == 0
                            && checkpoint.sourceToIndexExclusive() <= messages.size()
                            && checkpoint.sourceHash().equals(ContextSourceHash.compute(
                                    new Gson(), messages.subList(
                                            0, checkpoint.sourceToIndexExclusive())))) {
                        checkpoints = candidate;
                    }
                }
            }
            return new GuideHistoryContextSeed(
                    request.sessionId(), messages, checkpoints, estimated, oldest);
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_context_failed", "Unable to prepare guide history context", failure);
        } catch (IllegalArgumentException malformed) {
            throw new GuideHistoryException(
                    "history_corrupt", "Guide history context is malformed", malformed);
        }
    }

    @Override
    public void commit(GuideHistoryCommit commit) {
        Objects.requireNonNull(commit, "commit");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (GuideHistoryMutation mutation : commit.mutations()) {
                    applyMutation(connection, commit.scope(), mutation);
                }
                failureInjector.beforeCommit(Mutation.COMMIT);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof GuideHistoryException known) {
                    throw known;
                }
                throw new GuideHistoryException(
                        "history_write_failed", "Unable to commit durable guide history", failure);
            }
        } catch (SQLException failure) {
            throw new GuideHistoryException(
                    "history_write_failed", "Unable to commit durable guide history", failure);
        }
    }

    @Override
    public void delete(GuideHistoryDeleteScope scope) {
        Objects.requireNonNull(scope, "scope");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                switch (scope) {
                    case GuideHistoryDeleteScope.Partition partition ->
                        deletePartition(connection, partition.scope());
                    case GuideHistoryDeleteScope.Actor actor ->
                        deleteActor(connection, actor.actorId());
                }
                failureInjector.beforeCommit(Mutation.DELETE);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw deleteFailure(failure);
            }
        } catch (SQLException | RuntimeException failure) {
            if (failure instanceof GuideHistoryException historyFailure
                    && historyFailure.code().equals("history_delete_failed")) {
                throw historyFailure;
            }
            throw deleteFailure(failure);
        }
    }

    @Override
    public void resetDatabase() {
        try (Connection connection = openRaw()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("pragma foreign_keys=off");
            }
            connection.setAutoCommit(false);
            try {
                for (String table : applicationTables(connection)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("drop table " + quoteIdentifier(table));
                    }
                }
                createSchemaObjects(connection);
                failureInjector.beforeCommit(Mutation.RESET);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw deleteFailure(failure);
            }
        } catch (SQLException | RuntimeException failure) {
            if (failure instanceof GuideHistoryException historyFailure
                    && historyFailure.code().equals("history_delete_failed")) {
                throw historyFailure;
            }
            throw deleteFailure(failure);
        }
    }

    private Connection open() {
        Connection connection = openRaw();
        try {
            ensureSchema(connection);
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new GuideHistoryException(
                    "history_open_failed", "Unable to open the guide history database", failure);
        } catch (RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private Connection openRaw() {
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            try {
                configure(connection);
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

    private void ensureSchema(Connection connection) throws SQLException {
        List<String> tables = applicationTables(connection);
        if (!tables.contains("schema_metadata")) {
            if (!tables.isEmpty()) {
                throw unsupportedSchema("Guide history schema metadata is missing");
            }
            createSchema(connection);
            return;
        }
        if (!tableSignature(connection, "schema_metadata")
                .equals(HISTORY_SCHEMA_SIGNATURES.get(1).get("schema_metadata"))) {
            throw unsupportedSchema("Guide history schema metadata is inconsistent");
        }
        int version;
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select singleton, schema_version from schema_metadata")) {
            if (!result.next() || result.getInt("singleton") != 1) {
                throw unsupportedSchema("Guide history schema metadata is missing");
            }
            version = result.getInt("schema_version");
            if (result.wasNull() || result.next()) {
                throw unsupportedSchema("Guide history schema metadata is inconsistent");
            }
        }
        requireRecognizedSignature(connection, version, tables);
        if (version > 0 && version < SCHEMA_VERSION) {
            rebuildRecognizedSchema(connection, version, tables);
            return;
        }
        if (version != SCHEMA_VERSION) {
            throw unsupportedSchema("Unsupported guide history schema version " + version);
        }
    }

    private void rebuildRecognizedSchema(
            Connection connection, int version, List<String> tables) throws SQLException {
        requireRecognizedSignature(connection, version, tables);
        boolean autoCommit = connection.getAutoCommit();
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys=off");
        }
        connection.setAutoCommit(false);
        try {
            for (String table : tables) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("drop table " + quoteIdentifier(table));
                }
            }
            createSchemaObjects(connection);
            failureInjector.beforeCommit(Mutation.RESET);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw new GuideHistoryException(
                    "history_schema_rebuild_failed",
                    "Unable to rebuild pre-release guide history",
                    failure);
        } finally {
            connection.setAutoCommit(autoCommit);
            try (Statement statement = connection.createStatement()) {
                statement.execute("pragma foreign_keys=on");
            }
        }
    }

    private static void requireRecognizedSignature(
            Connection connection, int version, List<String> tables) throws SQLException {
        Map<String, List<ColumnSignature>> expected = HISTORY_SCHEMA_SIGNATURES.get(version);
        if (expected == null || !Set.copyOf(tables).equals(expected.keySet())) {
            throw unsupportedSchema("Guide history database has an unrecognized table set");
        }
        for (Map.Entry<String, List<ColumnSignature>> table : expected.entrySet()) {
            if (!tableSignature(connection, table.getKey()).equals(table.getValue())) {
                throw unsupportedSchema(
                        "Guide history table " + table.getKey() + " has an unrecognized structure");
            }
        }
    }

    private static List<ColumnSignature> tableSignature(Connection connection, String table)
            throws SQLException {
        List<ColumnSignature> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "pragma table_xinfo(" + quoteIdentifier(table) + ")")) {
            while (result.next()) {
                columns.add(new ColumnSignature(
                        result.getString("name"),
                        result.getString("type"),
                        result.getInt("notnull") != 0,
                        result.getInt("pk"),
                        result.getInt("hidden")));
            }
        }
        return List.copyOf(columns);
    }

    private static Map<Integer, Map<String, List<ColumnSignature>>> historySchemaSignatures() {
        Map<Integer, Map<String, List<ColumnSignature>>> versions = new LinkedHashMap<>();
        versions.put(1, historySchemaSignature(1));
        versions.put(2, historySchemaSignature(2));
        versions.put(3, historySchemaSignature(3));
        versions.put(4, historySchemaSignature(4));
        // Schema 5 changes the persisted payload contract, not the normalized SQLite columns.
        versions.put(5, historySchemaSignature(4));
        return Map.copyOf(versions);
    }

    private static Map<String, List<ColumnSignature>> historySchemaSignature(int version) {
        Map<String, List<ColumnSignature>> tables = new LinkedHashMap<>();
        tables.put("schema_metadata", columns(
                column("singleton", "INTEGER", false, 1),
                column("schema_version", "INTEGER", true, 0)));

        List<ColumnSignature> partitions = new ArrayList<>(List.of(
                column("scope_id", "TEXT", false, 1),
                column("actor_id", "TEXT", true, 0),
                column("connection_kind", "TEXT", true, 0),
                column("selected_session", "TEXT", true, 0)));
        if (version <= 2) {
            partitions.add(column("model_mode", "TEXT", true, 0));
        }
        partitions.add(column("capture_mode", "TEXT", true, 0));
        partitions.add(column("updated_at", "TEXT", true, 0));
        tables.put("partitions", List.copyOf(partitions));

        List<ColumnSignature> sessions = new ArrayList<>(List.of(
                column("scope_id", "TEXT", true, 1),
                column("session_id", "TEXT", true, 2),
                column("ordinal", "INTEGER", true, 0)));
        if (version >= 3) {
            sessions.add(column("model_selection_json", "TEXT", true, 0));
        }
        tables.put("sessions", List.copyOf(sessions));

        List<ColumnSignature> requests = new ArrayList<>(List.of(
                column("scope_id", "TEXT", true, 1),
                column("session_id", "TEXT", true, 0),
                column("request_id", "TEXT", true, 2),
                column(version >= 4 ? "sequence" : "ordinal", "INTEGER", true, 0),
                column("topology", "TEXT", true, 0)));
        if (version >= 3) {
            requests.add(column("model_selection_json", "TEXT", true, 0));
        }
        requests.addAll(List.of(
                column("user_message", "TEXT", true, 0),
                column("status", "TEXT", true, 0),
                column("input_tokens", "INTEGER", true, 0),
                column("output_tokens", "INTEGER", true, 0),
                column("cache_read_tokens", "INTEGER", true, 0),
                column("retry_after_millis", "INTEGER", false, 0),
                column("failure_code", "TEXT", false, 0),
                column("failure_message", "TEXT", false, 0),
                column("created_at", "TEXT", true, 0),
                column("updated_at", "TEXT", true, 0),
                column("terminal_at", "TEXT", false, 0)));
        tables.put("requests", List.copyOf(requests));

        tables.put("messages", columns(
                column("scope_id", "TEXT", true, 1),
                column("session_id", "TEXT", true, 2),
                column("ordinal", "INTEGER", true, 3),
                column("request_id", "TEXT", true, 0),
                column("role", "TEXT", true, 0),
                column("message_text", "TEXT", true, 0),
                column("created_at", "TEXT", true, 0)));
        tables.put("timeline_entries", payloadTableSignature());
        tables.put("request_sources", payloadTableSignature());
        if (version >= 2) {
            tables.put("compaction_checkpoints", columns(
                    column("scope_id", "TEXT", true, 1),
                    column("session_id", "TEXT", true, 2),
                    column("ordinal", "INTEGER", true, 3),
                    column("checkpoint_id", "TEXT", true, 0),
                    column("payload_json", "TEXT", true, 0)));
        }
        return Map.copyOf(tables);
    }

    private static List<ColumnSignature> payloadTableSignature() {
        return columns(
                column("scope_id", "TEXT", true, 1),
                column("request_id", "TEXT", true, 2),
                column("ordinal", "INTEGER", true, 3),
                column("payload_json", "TEXT", true, 0));
    }

    private static List<ColumnSignature> columns(ColumnSignature... columns) {
        return List.of(columns);
    }

    private static ColumnSignature column(
            String name, String type, boolean notNull, int primaryKeyPosition) {
        return new ColumnSignature(name, type, notNull, primaryKeyPosition, 0);
    }

    private static GuideHistoryException unsupportedSchema(String message) {
        return new GuideHistoryException("history_schema_unsupported", message);
    }

    private static void createSchema(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            createSchemaObjects(connection);
            connection.commit();
        } catch (SQLException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void createSchemaObjects(Connection connection) throws SQLException {
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
                        capture_mode text not null check(capture_mode = 'NORMAL'),
                        updated_at text not null
                    )
                    """);
            statement.execute("""
                    create table sessions(
                        scope_id text not null,
                        session_id text not null,
                        ordinal integer not null check(ordinal >= 0),
                        model_selection_json text not null,
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
                        sequence integer not null check(sequence >= 0),
                        topology text not null,
                        model_selection_json text not null,
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
                        unique(scope_id, session_id, sequence),
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
            statement.execute("create index requests_order_lookup on requests(scope_id, session_id, sequence)");
            statement.execute("create index timeline_order_lookup on timeline_entries(scope_id, request_id, ordinal)");
            createCheckpointTable(statement);
            statement.execute("insert into schema_metadata(singleton, schema_version) values (1, "
                    + SCHEMA_VERSION + ")");
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

    private static void deletePartition(Connection connection, GuideHistoryScope scope)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from partitions where scope_id = ? and actor_id = ?")) {
            delete.setString(1, scope.scopeId());
            delete.setString(2, scope.actorId().toString());
            delete.executeUpdate();
        }
    }

    private static void deleteActor(Connection connection, UUID actorId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from partitions where actor_id = ?")) {
            delete.setString(1, actorId.toString());
            delete.executeUpdate();
        }
    }

    private static List<String> applicationTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select name from sqlite_master
                        where type = 'table' and name not glob 'sqlite_*'
                        order by name
                        """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return List.copyOf(tables);
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static GuideHistoryException deleteFailure(Throwable failure) {
        return new GuideHistoryException(
                "history_delete_failed", "Unable to delete durable guide history", failure);
    }

    private void insertPartition(Connection connection, GuideHistoryPartition partition)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into partitions(
                    scope_id, actor_id, connection_kind, selected_session,
                    capture_mode, updated_at)
                values (?, ?, ?, ?, 'NORMAL', ?)
                """)) {
            insert.setString(1, partition.scope().scopeId());
            insert.setString(2, partition.scope().actorId().toString());
            insert.setString(3, partition.scope().kind().name());
            insert.setString(4, partition.selectedSession());
            insert.setString(5, partition.updatedAt().toString());
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

    private void insertSession(
            Connection connection,
            String scopeId,
            GuideSessionSnapshot session,
            int ordinal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into sessions(scope_id, session_id, ordinal, model_selection_json)
                values (?, ?, ?, ?)
                """)) {
            insert.setString(1, scopeId);
            insert.setString(2, session.sessionId());
            insert.setInt(3, ordinal);
            insert.setString(4, codec.encodeModelSelection(session.modelSelection()));
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
                    scope_id, session_id, request_id, sequence, topology, model_selection_json, user_message,
                    status, input_tokens, output_tokens, cache_read_tokens,
                    retry_after_millis, failure_code, failure_message,
                    created_at, updated_at, terminal_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, scopeId);
            insert.setString(2, sessionId);
            insert.setString(3, request.requestId().toString());
            insert.setInt(4, ordinal);
            insert.setString(5, request.topology().name());
            insert.setString(6, codec.encodeModelSelection(request.modelSelection()));
            insert.setString(7, request.userMessage());
            insert.setString(8, request.status().name());
            insert.setLong(9, request.usage().inputTokens());
            insert.setLong(10, request.usage().outputTokens());
            insert.setLong(11, request.usage().cacheReadTokens());
            nullableLong(insert, 12, request.retryAfterMillis());
            insert.setString(13, request.failure() == null ? null : request.failure().code());
            insert.setString(14, request.failure() == null ? null : request.failure().message());
            insert.setString(15, request.createdAt().toString());
            insert.setString(16, request.updatedAt().toString());
            insert.setString(17, request.terminalAt() == null ? null : request.terminalAt().toString());
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

    private void applyMutation(
            Connection connection,
            GuideHistoryScope scope,
            GuideHistoryMutation mutation) throws SQLException {
        String scopeId = scope.scopeId();
        switch (mutation) {
            case GuideHistoryMutation.UpsertPartition partition -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into partitions(
                            scope_id, actor_id, connection_kind, selected_session,
                            capture_mode, updated_at)
                        values (?, ?, ?, ?, 'NORMAL', ?)
                        on conflict(scope_id) do update set
                            selected_session = excluded.selected_session,
                            updated_at = excluded.updated_at
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, scope.actorId().toString());
                    statement.setString(3, scope.kind().name());
                    statement.setString(4, partition.selectedSession());
                    statement.setString(5, partition.updatedAt().toString());
                    statement.executeUpdate();
                }
            }
            case GuideHistoryMutation.UpsertSession session -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into sessions(scope_id, session_id, ordinal, model_selection_json)
                        values (?, ?, ?, ?)
                        on conflict(scope_id, session_id) do update set
                            ordinal = excluded.ordinal,
                            model_selection_json = excluded.model_selection_json
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, session.sessionId());
                    statement.setInt(3, session.ordinal());
                    statement.setString(4, codec.encodeModelSelection(session.modelSelection()));
                    statement.executeUpdate();
                }
            }
            case GuideHistoryMutation.UpsertRequest request ->
                    upsertRequest(connection, scopeId, request.sequence(), request.request());
            case GuideHistoryMutation.UpsertMessage message ->
                    upsertMessage(connection, scopeId, message);
            case GuideHistoryMutation.UpsertTimelineEntry timeline -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into timeline_entries(scope_id, request_id, ordinal, payload_json)
                        values (?, ?, ?, ?)
                        on conflict(scope_id, request_id, ordinal) do update set
                            payload_json = excluded.payload_json
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, timeline.requestId().toString());
                    statement.setInt(3, timeline.entry().ordinal());
                    statement.setString(4, codec.encodeEntry(timeline.entry()));
                    statement.executeUpdate();
                }
            }
            case GuideHistoryMutation.ReplaceRequestSources sources -> {
                try (PreparedStatement delete = connection.prepareStatement("""
                        delete from request_sources where scope_id = ? and request_id = ?
                        """)) {
                    delete.setString(1, scopeId);
                    delete.setString(2, sources.requestId().toString());
                    delete.executeUpdate();
                }
                for (int ordinal = 0; ordinal < sources.sources().size(); ordinal++) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            insert into request_sources(
                                scope_id, request_id, ordinal, payload_json)
                            values (?, ?, ?, ?)
                            """)) {
                        insert.setString(1, scopeId);
                        insert.setString(2, sources.requestId().toString());
                        insert.setInt(3, ordinal);
                        insert.setString(4, codec.encodeSources(
                                List.of(sources.sources().get(ordinal))));
                        insert.executeUpdate();
                    }
                }
            }
            case GuideHistoryMutation.UpsertCheckpoint checkpoint -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into compaction_checkpoints(
                            scope_id, session_id, ordinal, checkpoint_id, payload_json)
                        values (?, ?, ?, ?, ?)
                        on conflict(scope_id, session_id, ordinal) do update set
                            checkpoint_id = excluded.checkpoint_id,
                            payload_json = excluded.payload_json
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, checkpoint.sessionId());
                    statement.setInt(3, checkpoint.ordinal());
                    statement.setString(4, checkpoint.checkpoint().checkpointId().toString());
                    statement.setString(5, codec.encodeCheckpoint(checkpoint.checkpoint()));
                    statement.executeUpdate();
                }
            }
            case GuideHistoryMutation.DeleteSession session -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        delete from sessions where scope_id = ? and session_id = ?
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, session.sessionId());
                    statement.executeUpdate();
                }
            }
            case GuideHistoryMutation.ClearSession session -> {
                for (String table : List.of("messages", "compaction_checkpoints")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "delete from " + table + " where scope_id = ? and session_id = ?")) {
                        statement.setString(1, scopeId);
                        statement.setString(2, session.sessionId());
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        delete from requests where scope_id = ? and session_id = ?
                        """)) {
                    statement.setString(1, scopeId);
                    statement.setString(2, session.sessionId());
                    statement.executeUpdate();
                }
            }
        }
    }

    private void upsertRequest(
            Connection connection,
            String scopeId,
            long sequence,
            GuideRequestSnapshot request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requests(
                    scope_id, session_id, request_id, sequence, topology,
                    model_selection_json, user_message, status,
                    input_tokens, output_tokens, cache_read_tokens,
                    retry_after_millis, failure_code, failure_message,
                    created_at, updated_at, terminal_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(scope_id, request_id) do update set
                    session_id = excluded.session_id,
                    sequence = excluded.sequence,
                    topology = excluded.topology,
                    model_selection_json = excluded.model_selection_json,
                    user_message = excluded.user_message,
                    status = excluded.status,
                    input_tokens = excluded.input_tokens,
                    output_tokens = excluded.output_tokens,
                    cache_read_tokens = excluded.cache_read_tokens,
                    retry_after_millis = excluded.retry_after_millis,
                    failure_code = excluded.failure_code,
                    failure_message = excluded.failure_message,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    terminal_at = excluded.terminal_at
                """)) {
            statement.setString(1, scopeId);
            statement.setString(2, request.sessionId());
            statement.setString(3, request.requestId().toString());
            statement.setLong(4, sequence);
            statement.setString(5, request.topology().name());
            statement.setString(6, codec.encodeModelSelection(request.modelSelection()));
            statement.setString(7, request.userMessage());
            statement.setString(8, request.status().name());
            statement.setLong(9, request.usage().inputTokens());
            statement.setLong(10, request.usage().outputTokens());
            statement.setLong(11, request.usage().cacheReadTokens());
            nullableLong(statement, 12, request.retryAfterMillis());
            statement.setString(13, request.failure() == null ? null : request.failure().code());
            statement.setString(14, request.failure() == null ? null : request.failure().message());
            statement.setString(15, request.createdAt().toString());
            statement.setString(16, request.updatedAt().toString());
            statement.setString(17,
                    request.terminalAt() == null ? null : request.terminalAt().toString());
            statement.executeUpdate();
        }
    }

    private static void upsertMessage(
            Connection connection,
            String scopeId,
            GuideHistoryMutation.UpsertMessage mutation) throws SQLException {
        GuideMessage message = mutation.message();
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into messages(
                    scope_id, session_id, ordinal, request_id, role, message_text, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict(scope_id, session_id, ordinal) do update set
                    request_id = excluded.request_id,
                    role = excluded.role,
                    message_text = excluded.message_text,
                    created_at = excluded.created_at
                """)) {
            statement.setString(1, scopeId);
            statement.setString(2, mutation.sessionId());
            statement.setInt(3, mutation.ordinal());
            statement.setString(4, message.requestId().toString());
            statement.setString(5, message.role().name());
            statement.setString(6, message.text());
            statement.setString(7, message.createdAt().toString());
            statement.executeUpdate();
        }
    }

    private GuideHistoryCursor cursor(
            Connection connection,
            String scopeId,
            String sessionId,
            long sequence) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select request_id from requests
                where scope_id = ? and session_id = ? and sequence = ?
                """)) {
            query.setString(1, scopeId);
            query.setString(2, sessionId);
            query.setLong(3, sequence);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("history cursor row is missing");
                }
                return new GuideHistoryCursor(sequence,
                        UUID.fromString(result.getString("request_id")));
            }
        }
    }

    private List<SequencedRequest> readPage(
            Connection connection,
            GuideHistoryPageRequest request) throws SQLException {
        if (request.cursor() != null) {
            requireCursor(connection, request);
        }
        String comparison = switch (request.direction()) {
            case NEWEST -> "";
            case BEFORE -> " and sequence < ?";
            case AFTER -> " and sequence > ?";
        };
        String order = request.direction() == GuideHistoryPageRequest.Direction.AFTER
                ? " order by sequence asc limit ?"
                : " order by sequence desc limit ?";
        String sql = requestColumns()
                + " from requests where scope_id = ? and session_id = ?"
                + comparison + order;
        List<SequencedRequest> loaded = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, request.scope().scopeId());
            query.setString(2, request.sessionId());
            int next = 3;
            if (request.cursor() != null) {
                query.setLong(next++, request.cursor().sequence());
            }
            query.setInt(next, request.count());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    loaded.add(readRequestRow(
                            connection, request.scope().scopeId(), result));
                }
            }
        }
        if (request.direction() != GuideHistoryPageRequest.Direction.AFTER) {
            java.util.Collections.reverse(loaded);
        }
        return List.copyOf(loaded);
    }

    private void requireCursor(Connection connection, GuideHistoryPageRequest request)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select request_id from requests
                where scope_id = ? and session_id = ? and sequence = ?
                """)) {
            query.setString(1, request.scope().scopeId());
            query.setString(2, request.sessionId());
            query.setLong(3, request.cursor().sequence());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !request.cursor().requestId().toString()
                        .equals(result.getString("request_id"))) {
                    throw new GuideHistoryException(
                            "history_cursor_stale", "Guide history cursor is stale");
                }
            }
        }
    }

    private static boolean requestExists(
            Connection connection,
            String scopeId,
            String sessionId,
            String comparison,
            long sequence) throws SQLException {
        if (!comparison.equals("sequence < ?") && !comparison.equals("sequence > ?")) {
            throw new IllegalArgumentException("unsupported history comparison");
        }
        try (PreparedStatement query = connection.prepareStatement(
                "select 1 from requests where scope_id = ? and session_id = ? and "
                        + comparison + " limit 1")) {
            query.setString(1, scopeId);
            query.setString(2, sessionId);
            query.setLong(3, sequence);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String requestColumns() {
        return """
                select sequence, session_id, request_id, topology, user_message, status,
                       model_selection_json,
                       input_tokens, output_tokens, cache_read_tokens,
                       retry_after_millis, failure_code, failure_message,
                       created_at, updated_at, terminal_at
                """;
    }

    private SequencedRequest readRequestRow(
            Connection connection,
            String scopeId,
            ResultSet result) throws SQLException {
        UUID requestId = UUID.fromString(result.getString("request_id"));
        String sessionId = result.getString("session_id");
        if (sessionId == null) {
            sessionId = currentSessionForRequest(connection, scopeId, requestId);
        }
        GuideFailure failure = result.getString("failure_code") == null
                ? null
                : new GuideFailure(
                        result.getString("failure_code"),
                        required(result.getString("failure_message"), "failure message"));
        long sequence = result.getLong("sequence");
        GuideRequestSnapshot request = new GuideRequestSnapshot(
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
                nullableInstant(result.getString("terminal_at")),
                codec.decodeModelSelection(result.getString("model_selection_json")));
        return new SequencedRequest(new GuideHistoryCursor(sequence, requestId), request);
    }

    private static String currentSessionForRequest(
            Connection connection, String scopeId, UUID requestId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id from requests where scope_id = ? and request_id = ?
                """)) {
            query.setString(1, scopeId);
            query.setString(2, requestId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("durable request row disappeared");
                }
                return result.getString("session_id");
            }
        }
    }

    private List<ContextCheckpoint> readApplicableCheckpoint(
            Connection connection,
            String scopeId,
            String sessionId,
            String modelIdentifier) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select payload_json from compaction_checkpoints
                where scope_id = ? and session_id = ? order by ordinal desc
                """)) {
            query.setString(1, scopeId);
            query.setString(2, sessionId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ContextCheckpoint checkpoint = codec.decodeCheckpoint(
                            result.getString("payload_json"));
                    if (checkpoint.status() == ContextCheckpoint.Status.SUCCEEDED
                            && checkpoint.modelIdentifier().equals(modelIdentifier)) {
                        return List.of(checkpoint);
                    }
                }
            }
        }
        return List.of();
    }

    private static List<ModelMessage> contextMessages(GuideRequestSnapshot request) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.userText(request.userMessage()));
        for (GuideTimelineEntry entry : request.timeline()) {
            switch (entry) {
                case GuideTimelineEntry.Assistant assistant -> {
                    if (!assistant.text().isBlank()) {
                        messages.add(new ModelMessage(
                                ModelRole.ASSISTANT,
                                List.of(new ModelContent.Text(assistant.text()))));
                    }
                }
                case GuideTimelineEntry.Tool tool -> {
                    String durableInvocationId = request.requestId()
                            + ":" + tool.activity().invocationId();
                    JsonObject input = new JsonObject();
                    input.addProperty("durableProjection", true);
                    messages.add(new ModelMessage(
                            ModelRole.ASSISTANT,
                            List.of(new ModelContent.ToolUse(
                                    durableInvocationId,
                                    tool.activity().toolId(),
                                    input))));
                    JsonObject result = new JsonObject();
                    result.addProperty("status", tool.activity().status().name());
                    result.add("presentationMessages", GuideToolMessageCodec.encode(
                            tool.activity().presentationMessages()));
                    JsonArray sources = new JsonArray();
                    for (GuideSource source : tool.activity().sources()) {
                        JsonObject encoded = new JsonObject();
                        encoded.addProperty("sourceId", source.evidence().sourceId());
                        encoded.addProperty("provenance", source.evidence().provenance());
                        encoded.addProperty("authority", source.evidence().authority().name());
                        encoded.addProperty("completeness", source.evidence().completeness().name());
                        sources.add(encoded);
                    }
                    result.add("sources", sources);
                    messages.add(new ModelMessage(
                            ModelRole.USER,
                            List.of(new ModelContent.ToolResult(
                                    durableInvocationId,
                                    result,
                                    tool.activity().status()
                                            != dev.openallay.guide.GuideToolStatus.SUCCEEDED))));
                }
            }
        }
        dev.openallay.agent.context.ContextStructure.units(messages);
        return List.copyOf(messages);
    }

    private void recoverInterruptedRows(Connection connection, GuideHistoryScope scope)
            throws SQLException {
        List<UUID> active = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                select request_id from requests
                where scope_id = ? and terminal_at is null
                """)) {
            query.setString(1, scope.scopeId());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    active.add(UUID.fromString(result.getString("request_id")));
                }
            }
        }
        if (active.isEmpty()) {
            return;
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Instant recoveredAt = clock.instant();
        try {
            for (UUID requestId : active) {
                for (GuideTimelineEntry entry : readTimeline(
                        connection, scope.scopeId(), requestId)) {
                    if (entry instanceof GuideTimelineEntry.Assistant assistant
                            && assistant.streaming()) {
                        GuideTimelineEntry.Assistant closed = new GuideTimelineEntry.Assistant(
                                assistant.ordinal(), assistant.text(), assistant.semantic(),
                                false, assistant.sources());
                        try (PreparedStatement update = connection.prepareStatement("""
                                update timeline_entries set payload_json = ?
                                where scope_id = ? and request_id = ? and ordinal = ?
                                """)) {
                            update.setString(1, codec.encodeEntry(closed));
                            update.setString(2, scope.scopeId());
                            update.setString(3, requestId.toString());
                            update.setInt(4, closed.ordinal());
                            update.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        update requests set status = 'INTERRUPTED',
                            retry_after_millis = null,
                            failure_code = 'request_interrupted',
                            failure_message = ?, updated_at = ?, terminal_at = ?
                        where scope_id = ? and request_id = ? and terminal_at is null
                        """)) {
                    update.setString(1,
                            "The previous client process ended before this request completed");
                    update.setString(2, recoveredAt.toString());
                    update.setString(3, recoveredAt.toString());
                    update.setString(4, scope.scopeId());
                    update.setString(5, requestId.toString());
                    update.executeUpdate();
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    update partitions set updated_at = ? where scope_id = ?
                    """)) {
                update.setString(1, recoveredAt.toString());
                update.setString(2, scope.scopeId());
                update.executeUpdate();
            }
            failureInjector.beforeCommit(Mutation.RECOVER);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
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
                snapshots,
                header.updatedAt);
    }

    private static PartitionHeader readHeader(Connection connection, GuideHistoryScope scope)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                select actor_id, connection_kind, selected_session, capture_mode, updated_at
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
                        Instant.parse(result.getString("updated_at")));
            }
        }
    }

    private LinkedHashMap<String, SessionBuilder> readSessions(
            Connection connection, String scopeId) throws SQLException {
        LinkedHashMap<String, SessionBuilder> sessions = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                select session_id, model_selection_json
                from sessions where scope_id = ? order by ordinal
                """)) {
            query.setString(1, scopeId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String sessionId = result.getString(1);
                    GuideModelSelection selection = codec.decodeModelSelection(
                            required(result.getString("model_selection_json"), "session model selection"));
                    if (sessions.put(sessionId, new SessionBuilder(sessionId, selection)) != null) {
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
                       model_selection_json,
                       input_tokens, output_tokens, cache_read_tokens, retry_after_millis,
                       failure_code, failure_message, created_at, updated_at, terminal_at
                from requests where scope_id = ? order by session_id, sequence
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
                            nullableInstant(result.getString("terminal_at")),
                            codec.decodeModelSelection(required(
                                    result.getString("model_selection_json"),
                                    "request model selection"))));
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
                        recoveredAt,
                        request.modelSelection()));
            }
            sessions.add(new GuideSessionSnapshot(
                    session.sessionId(),
                    session.messages(),
                    requests,
                    session.checkpoints(),
                    session.modelSelection()));
        }
        if (!changed) {
            return partition;
        }
        return new GuideHistoryPartition(
                partition.schemaVersion(),
                partition.scope(),
                partition.selectedSession(),
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
            Instant updatedAt) {}

    private record SequencedRequest(
            GuideHistoryCursor cursor,
            GuideRequestSnapshot request) {}

    private record ColumnSignature(
            String name,
            String type,
            boolean notNull,
            int primaryKeyPosition,
            int hidden) {}

    enum Mutation {
        COMMIT,
        RECOVER,
        DELETE,
        RESET
    }

    @FunctionalInterface
    interface FailureInjector {
        void beforeCommit(Mutation mutation) throws SQLException;
    }

    private static final class SessionBuilder {
        private final String id;
        private final GuideModelSelection modelSelection;
        private final List<GuideMessage> messages = new ArrayList<>();
        private final List<GuideRequestSnapshot> requests = new ArrayList<>();
        private final List<ContextCheckpoint> checkpoints = new ArrayList<>();

        private SessionBuilder(String id, GuideModelSelection modelSelection) {
            this.id = id;
            this.modelSelection = modelSelection;
        }

        private GuideSessionSnapshot snapshot() {
            return new GuideSessionSnapshot(id, messages, requests, checkpoints, modelSelection);
        }
    }
}
