package dev.openallay.model.config;

import dev.openallay.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Dedicated local credential database; no secret is exposed through observable settings state. */
public final class LocalCredentialStore implements CredentialResolver, AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path database;
    private final Clock clock;
    private boolean closed;

    public LocalCredentialStore(Path database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized ToolResult<CredentialReference> insert(SecretValue secret) {
        Objects.requireNonNull(secret, "secret");
        if (closed) {
            return unavailable();
        }
        CredentialReference reference = CredentialReference.local(UUID.randomUUID());
        byte[] encoded = secret.reveal().getBytes(StandardCharsets.UTF_8);
        try (Connection connection = open();
                var statement = connection.prepareStatement("""
                        insert into credentials(
                            credential_id, secret_value, created_at, updated_at)
                        values (?, ?, ?, ?)
                        """)) {
            String now = Instant.now(clock).toString();
            statement.setString(1, reference.value());
            statement.setBytes(2, encoded);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
            return new ToolResult.Success<>(reference);
        } catch (SQLException | RuntimeException failure) {
            return unavailable();
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    @Override
    public synchronized ToolResult<SecretValue> resolve(CredentialReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (closed || reference.kind() != CredentialReference.Kind.LOCAL) {
            return unavailable();
        }
        try (Connection connection = open();
                var statement = connection.prepareStatement(
                        "select secret_value from credentials where credential_id = ?")) {
            statement.setString(1, reference.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new ToolResult.Failure<>(
                            "credential_not_found", "The stored credential is unavailable");
                }
                byte[] encoded = result.getBytes(1);
                try {
                    return new ToolResult.Success<>(
                            SecretValue.of(new String(encoded, StandardCharsets.UTF_8)));
                } finally {
                    java.util.Arrays.fill(encoded, (byte) 0);
                }
            }
        } catch (SQLException | RuntimeException failure) {
            return unavailable();
        }
    }

    /** Checks a local reference without materializing its secret value. */
    public synchronized ToolResult<Boolean> contains(CredentialReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (closed || reference.kind() != CredentialReference.Kind.LOCAL) {
            return unavailable();
        }
        try (Connection connection = open();
                var statement = connection.prepareStatement(
                        "select 1 from credentials where credential_id = ?")) {
            statement.setString(1, reference.value());
            try (ResultSet result = statement.executeQuery()) {
                return new ToolResult.Success<>(result.next());
            }
        } catch (SQLException | RuntimeException failure) {
            return unavailable();
        }
    }

    public synchronized ToolResult<Boolean> deleteIfUnreferenced(
            CredentialReference reference,
            Set<CredentialReference> retained) {
        Objects.requireNonNull(reference, "reference");
        Set<CredentialReference> retainedCopy = Set.copyOf(retained);
        if (closed) {
            return unavailable();
        }
        if (reference.kind() != CredentialReference.Kind.LOCAL || retainedCopy.contains(reference)) {
            return new ToolResult.Success<>(Boolean.FALSE);
        }
        try (Connection connection = open();
                var statement = connection.prepareStatement(
                        "delete from credentials where credential_id = ?")) {
            statement.setString(1, reference.value());
            return new ToolResult.Success<>(statement.executeUpdate() > 0);
        } catch (SQLException | RuntimeException failure) {
            return unavailable();
        }
    }

    public synchronized ToolResult<Integer> collectUnreferenced(
            Set<CredentialReference> retained) {
        Set<String> retainedIds = new HashSet<>();
        for (CredentialReference reference : Set.copyOf(retained)) {
            if (reference.kind() == CredentialReference.Kind.LOCAL) {
                retainedIds.add(reference.value());
            }
        }
        if (closed) {
            return unavailable();
        }
        try (Connection connection = open()) {
            int deleted = 0;
            try (var query = connection.createStatement();
                    ResultSet result = query.executeQuery(
                            "select credential_id from credentials order by credential_id")) {
                java.util.List<String> candidates = new java.util.ArrayList<>();
                while (result.next()) {
                    String id = result.getString(1);
                    if (!retainedIds.contains(id)) {
                        candidates.add(id);
                    }
                }
                try (var remove = connection.prepareStatement(
                        "delete from credentials where credential_id = ?")) {
                    for (String id : candidates) {
                        remove.setString(1, id);
                        deleted += remove.executeUpdate();
                    }
                }
            }
            return new ToolResult.Success<>(deleted);
        } catch (SQLException | RuntimeException failure) {
            return unavailable();
        }
    }

    private Connection open() throws SQLException {
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException failure) {
            throw new SQLException("credential directory unavailable", failure);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        boolean success = false;
        try {
            configure(connection);
            ensureSchema(connection);
            hardenPermissions();
            success = true;
            return connection;
        } finally {
            if (!success) {
                connection.close();
            }
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma journal_mode=wal");
            statement.execute("pragma synchronous=full");
        }
    }

    private static void ensureSchema(Connection connection) throws SQLException {
        boolean metadataExists = tableExists(connection, "schema_metadata");
        boolean credentialsExist = tableExists(connection, "credentials");
        if (!metadataExists && !credentialsExist) {
            if (!applicationTables(connection).isEmpty()) {
                throw new SQLException("unrecognized credential database");
            }
            createSchema(connection);
            return;
        }
        if (!metadataExists || !credentialsExist || !applicationTables(connection).equals(
                Set.of("schema_metadata", "credentials"))) {
            throw new SQLException("unrecognized credential database");
        }
        try (Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "select schema_version from schema_metadata where singleton = 1")) {
                if (!result.next() || result.getInt(1) != SCHEMA_VERSION || result.next()) {
                    throw new SQLException("unsupported credential schema");
                }
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
                    create table credentials(
                        credential_id text primary key,
                        secret_value blob not null,
                        created_at text not null,
                        updated_at text not null
                    )
                    """);
            statement.execute("insert into schema_metadata(singleton, schema_version) "
                    + "values (1, " + SCHEMA_VERSION + ")");
            connection.commit();
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement(
                "select 1 from sqlite_master where type = 'table' and name = ?")) {
            query.setString(1, table);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Set<String> applicationTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        select name from sqlite_master
                        where type = 'table' and name not glob 'sqlite_*'
                        """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return Set.copyOf(tables);
    }

    private void hardenPermissions() {
        try {
            if (Files.getFileStore(database).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(database, OWNER_ONLY);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // The store remains usable but callers must not claim OS-vault protection.
        }
    }

    private static <T> ToolResult.Failure<T> unavailable() {
        return new ToolResult.Failure<>(
                "credential_store_unavailable", "Stored credentials are unavailable");
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
