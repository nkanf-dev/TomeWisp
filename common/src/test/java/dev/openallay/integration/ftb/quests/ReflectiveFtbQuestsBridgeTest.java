package dev.openallay.integration.ftb.quests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ReflectiveFtbQuestsBridgeTest {
    @Test
    void snapshotsOnlyVisibleQuestsAndVisibleDependencies() {
        FtbQuestSnapshot.Result result = new ReflectiveFtbQuestsBridge(
                        getClass().getClassLoader(), Fixtures.ApiRoot.class.getName())
                .snapshot(new Fixtures.Player(), true);

        assertTrue(result.available(), result.diagnosticMessage());
        assertEquals(List.of("1", "2"), result.quests().stream()
                .map(FtbQuestSnapshot::questId).toList());
        assertEquals(java.util.Set.of("1"), result.quests().get(1).dependencyIds());
        assertTrue(result.quests().getFirst().completed());
        assertFalse(result.quests().stream().anyMatch(quest -> quest.title().equals("Hidden")));
    }

    @Test
    void reportsAbsentOrMismatchedApiWithoutPrivateReflectionFallback() {
        assertEquals("integration_unavailable",
                new ReflectiveFtbQuestsBridge(getClass().getClassLoader(), "missing.Api")
                        .snapshot(new Object(), true).diagnosticCode());
        assertEquals("integration_shape_mismatch",
                new ReflectiveFtbQuestsBridge(
                                getClass().getClassLoader(), Fixtures.BadApiRoot.class.getName())
                        .snapshot(new Object(), true).diagnosticCode());
    }

    public static final class Fixtures {
        public static final class ApiRoot {
            public static Api api() { return new Api(); }
        }
        public static final class BadApiRoot {
            public static Object api(String wrong) { return wrong; }
        }
        public static final class Api {
            public File getQuestFile(boolean client) { return new File(); }
        }
        public static final class Player {}
        public static final class File {
            public Optional<Team> getTeamData(Object player) { return Optional.of(new Team()); }
            public List<Chapter> getVisibleChapters(Team team) { return List.of(new Chapter()); }
        }
        public static final class Team {
            public boolean isCompleted(Quest quest) { return quest.id == 1; }
        }
        public static final class Chapter {
            public long getId() { return 10; }
            public Component getTitle() { return new Component("Chapter"); }
            public List<Quest> getQuests() {
                Quest first = new Quest(1, "First", true);
                Quest second = new Quest(2, "Second", true);
                Quest hidden = new Quest(3, "Hidden", false);
                second.dependencies = List.of(first, hidden);
                return List.of(first, second, hidden);
            }
        }
        public static final class Quest {
            private final long id;
            private final String title;
            private final boolean visible;
            private List<Quest> dependencies = List.of();
            Quest(long id, String title, boolean visible) {
                this.id = id; this.title = title; this.visible = visible;
            }
            public long getId() { return id; }
            public Component getTitle() { return new Component(title); }
            public List<Component> getDescription() { return List.of(new Component("Description " + id)); }
            public boolean isVisible(Team team) { return visible; }
            public Stream<Quest> streamDependencies() { return dependencies.stream(); }
        }
        public static final class Component {
            private final String value;
            Component(String value) { this.value = value; }
            public String getString() { return value; }
        }
    }
}
