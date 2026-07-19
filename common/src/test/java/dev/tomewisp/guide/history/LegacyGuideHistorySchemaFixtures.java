package dev.tomewisp.guide.history;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class LegacyGuideHistorySchemaFixtures {
    private LegacyGuideHistorySchemaFixtures() {}

    static void create(Path database, int version) throws SQLException {
        if (version < 1 || version > 4) {
            throw new IllegalArgumentException("unsupported legacy schema " + version);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys=on");
            statement.execute("""
                    create table schema_metadata(
                        singleton integer primary key check(singleton = 1),
                        schema_version integer not null
                    )
                    """);
            statement.execute(version <= 2 ? partitionsWithModelMode() : partitions());
            statement.execute(version <= 2 ? sessionsWithoutSelection() : sessionsWithSelection());
            statement.execute(requests(version));
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
            createPayloadTable(statement, "timeline_entries");
            createPayloadTable(statement, "request_sources");
            statement.execute("create index sessions_updated_lookup on sessions(scope_id, ordinal)");
            statement.execute("create index requests_order_lookup on requests(scope_id, session_id, "
                    + (version >= 4 ? "sequence" : "ordinal") + ")");
            statement.execute("create index timeline_order_lookup on timeline_entries(scope_id, request_id, ordinal)");
            if (version >= 2) {
                statement.execute("""
                        create table compaction_checkpoints(
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
                        create index checkpoint_order_lookup
                        on compaction_checkpoints(scope_id, session_id, ordinal)
                        """);
            }
            statement.execute("insert into schema_metadata(singleton, schema_version) values (1, "
                    + version + ")");
        }
    }

    private static String partitionsWithModelMode() {
        return """
                create table partitions(
                    scope_id text primary key,
                    actor_id text not null,
                    connection_kind text not null,
                    selected_session text not null,
                    model_mode text not null,
                    capture_mode text not null check(capture_mode = 'NORMAL'),
                    updated_at text not null
                )
                """;
    }

    private static String partitions() {
        return """
                create table partitions(
                    scope_id text primary key,
                    actor_id text not null,
                    connection_kind text not null,
                    selected_session text not null,
                    capture_mode text not null check(capture_mode = 'NORMAL'),
                    updated_at text not null
                )
                """;
    }

    private static String sessionsWithoutSelection() {
        return """
                create table sessions(
                    scope_id text not null,
                    session_id text not null,
                    ordinal integer not null check(ordinal >= 0),
                    primary key(scope_id, session_id),
                    unique(scope_id, ordinal),
                    foreign key(scope_id) references partitions(scope_id) on delete cascade
                )
                """;
    }

    private static String sessionsWithSelection() {
        return """
                create table sessions(
                    scope_id text not null,
                    session_id text not null,
                    ordinal integer not null check(ordinal >= 0),
                    model_selection_json text not null,
                    primary key(scope_id, session_id),
                    unique(scope_id, ordinal),
                    foreign key(scope_id) references partitions(scope_id) on delete cascade
                )
                """;
    }

    private static String requests(int version) {
        String sequence = version >= 4 ? "sequence" : "ordinal";
        String selection = version >= 3 ? "model_selection_json text not null," : "";
        return """
                create table requests(
                    scope_id text not null,
                    session_id text not null,
                    request_id text not null,
                    %s integer not null check(%s >= 0),
                    topology text not null,
                    %s
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
                    unique(scope_id, session_id, %s),
                    foreign key(scope_id, session_id)
                        references sessions(scope_id, session_id) on delete cascade
                )
                """.formatted(sequence, sequence, selection, sequence);
    }

    private static void createPayloadTable(Statement statement, String table) throws SQLException {
        statement.execute("""
                create table %s(
                    scope_id text not null,
                    request_id text not null,
                    ordinal integer not null check(ordinal >= 0),
                    payload_json text not null,
                    primary key(scope_id, request_id, ordinal),
                    foreign key(scope_id, request_id)
                        references requests(scope_id, request_id) on delete cascade
                )
                """.formatted(table));
    }
}
