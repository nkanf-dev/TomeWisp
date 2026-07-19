package dev.tomewisp.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalCredentialStoreTest {
    @TempDir Path temporary;

    @Test
    void storesResolvesAndCollectsOpaqueLocalCredentials() throws Exception {
        Path path = temporary.resolve("credentials.sqlite3");
        LocalCredentialStore store = new LocalCredentialStore(
                path, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        CredentialReference first = success(store.insert(SecretValue.of("secret-one")));
        CredentialReference second = success(store.insert(SecretValue.of("secret-two")));

        assertEquals(CredentialReference.Kind.LOCAL, first.kind());
        assertTrue(success(store.contains(first)));
        assertTrue(success(store.contains(second)));
        assertEquals("secret-one", success(store.resolve(first)).reveal());
        assertFalse(first.toString().contains("secret-one"));
        assertEquals(1, success(store.collectUnreferenced(Set.of(first))));
        assertEquals("credential_not_found", failure(store.resolve(second)).code());
        assertFalse(success(store.contains(second)));
        assertEquals("secret-one", success(store.resolve(first)).reveal());
        assertTrue(Files.exists(path));
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
        }
    }

    @Test
    void sharedReferenceIsNotDeletedAndClosedStoreFailsRedacted() {
        LocalCredentialStore store = new LocalCredentialStore(
                temporary.resolve("shared.sqlite3"), Clock.systemUTC());
        CredentialReference reference = success(store.insert(SecretValue.of("shared-secret")));

        assertFalse(success(store.deleteIfUnreferenced(reference, Set.of(reference))));
        assertEquals("shared-secret", success(store.resolve(reference)).reveal());
        store.close();

        ToolResult.Failure<SecretValue> failure = failure(store.resolve(reference));
        assertEquals("credential_store_unavailable", failure.code());
        assertFalse(failure.toString().contains("shared-secret"));
    }

    @Test
    void futureSchemaFailsClosedWithoutDeletingRows() throws Exception {
        Path path = temporary.resolve("future.sqlite3");
        LocalCredentialStore store = new LocalCredentialStore(path, Clock.systemUTC());
        CredentialReference reference = success(store.insert(SecretValue.of("retained-secret")));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update schema_metadata set schema_version = 99 where singleton = 1");
        }

        assertEquals("credential_store_unavailable", failure(store.resolve(reference)).code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from credentials")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void foreignDatabaseIsNotModified() throws Exception {
        Path path = temporary.resolve("foreign.sqlite3");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement()) {
            statement.execute("create table unrelated(value text)");
            statement.execute("insert into unrelated(value) values ('retained')");
        }
        LocalCredentialStore store = new LocalCredentialStore(path, Clock.systemUTC());

        assertEquals(
                "credential_store_unavailable",
                failure(store.insert(SecretValue.of("must-not-persist"))).code());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement();
                var result = statement.executeQuery("select value from unrelated")) {
            assertTrue(result.next());
            assertEquals("retained", result.getString(1));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(ToolResult<T> result) {
        return ((ToolResult.Success<T>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> ToolResult.Failure<T> failure(ToolResult<T> result) {
        return (ToolResult.Failure<T>) assertInstanceOf(ToolResult.Failure.class, result);
    }
}
