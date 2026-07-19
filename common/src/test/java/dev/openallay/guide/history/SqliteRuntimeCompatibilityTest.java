package dev.openallay.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteRuntimeCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void opensWalDatabaseAndCommitsTransaction() throws Exception {
        Path database = temporary.resolve("history.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("pragma journal_mode=wal")) {
                    assertTrue(result.next());
                    assertEquals("wal", result.getString(1).toLowerCase(Locale.ROOT));
                }
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

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected the extracted SQLite driver path");
        }
        Path expectedDriver = Path.of(arguments[0]).toRealPath();
        Path loadedDriver = Path.of(
                        Class.forName("org.sqlite.JDBC")
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                .toRealPath();
        if (!expectedDriver.equals(loadedDriver)) {
            throw new IllegalStateException(
                    "SQLite driver loaded from " + loadedDriver + " instead of " + expectedDriver);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select sqlite_version()")) {
            if (!result.next()) {
                throw new IllegalStateException("SQLite version query returned no row");
            }
            System.out.println("sqlite=" + result.getString(1) + " source=" + loadedDriver);
        }
    }
}
