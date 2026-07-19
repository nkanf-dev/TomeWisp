package dev.openallay.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.guide.semantic.SemanticDocument;
import dev.openallay.guide.semantic.SemanticMessageParser;
import dev.openallay.guide.ui.GuideTranscriptVirtualizer;
import dev.openallay.guide.ui.GuideViewportAnchor;
import dev.openallay.model.ModelUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evidence-scale gate. Counts are coverage fixtures, not product limits. */
final class Phase4SemanticHistoryScaleTest {
    private static final int REQUESTS = 10_000;
    private static final int TIMELINE_ROWS = REQUESTS * 3;
    private static final Instant NOW = Instant.parse("2026-07-18T16:00:00Z");
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            UUID.fromString("17300b31-4bfd-440a-a72e-a89eb5f00f85"),
            GuideHistoryScope.Kind.MULTIPLAYER,
            "scale.example");

    @TempDir Path temporary;

    @Test
    void tensOfThousandsOfSemanticRowsStayWindowedBudgetedAndIncremental()
            throws Exception {
        Path database = temporary.resolve("scale.db");
        SqliteGuideHistoryStore store = new SqliteGuideHistoryStore(
                database, Clock.fixed(NOW, ZoneOffset.UTC), new GuideHistoryCodec());
        GuideHistoryPartition fixture = fixture();

        long saveStarted = System.nanoTime();
        store.save(fixture);
        long saveMillis = elapsedMillis(saveStarted);

        long metadataStarted = System.nanoTime();
        GuideHistoryMetadata metadata = store.metadata(SCOPE).orElseThrow();
        long metadataMillis = elapsedMillis(metadataStarted);
        assertEquals(REQUESTS, metadata.sessions().getFirst().requestCount());

        long pageStarted = System.nanoTime();
        GuideHistoryPage page = store.page(new GuideHistoryPageRequest(
                SCOPE, "main", GuideHistoryPageRequest.Direction.NEWEST, null, 9));
        long pageMillis = elapsedMillis(pageStarted);
        assertEquals(9, page.requests().size());
        assertTrue(page.hasEarlier());

        GuideHistoryContextRequest contextRequest = new GuideHistoryContextRequest(
                SCOPE, "main", new ContextBudget(1_200, 200), 100, "scale-model");
        long contextStarted = System.nanoTime();
        GuideHistoryContextSeed context = store.context(contextRequest);
        long contextMillis = elapsedMillis(contextStarted);
        assertTrue(context.estimatedTokens() <= contextRequest.availableHistoryTokens());
        assertTrue(context.messages().size() < TIMELINE_ROWS);

        long unrelatedRequestRow = rowId(database, "requests", requestId(0), null);
        long unrelatedTimelineRow = rowId(database, "timeline_entries", requestId(0), 0);
        GuideRequestSnapshot target = fixture.sessions().getFirst().requests().get(5_000);
        GuideTimelineEntry.Assistant replacement = new GuideTimelineEntry.Assistant(
                2, "# Updated\n\nOnly this semantic row changed.", false, List.of());
        long updateStarted = System.nanoTime();
        store.commit(new GuideHistoryCommit(SCOPE, List.of(
                new GuideHistoryMutation.UpsertPartition("main", NOW.plusSeconds(20_000)),
                new GuideHistoryMutation.UpsertTimelineEntry(
                        target.requestId(), replacement))));
        long updateMillis = elapsedMillis(updateStarted);
        assertEquals(unrelatedRequestRow, rowId(database, "requests", requestId(0), null));
        assertEquals(unrelatedTimelineRow,
                rowId(database, "timeline_entries", requestId(0), 0));
        assertEquals(TIMELINE_ROWS, count(database, "timeline_entries"));

        GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
        ArrayList<GuideTranscriptVirtualizer.Row> virtualRows = new ArrayList<>(TIMELINE_ROWS);
        for (int index = 0; index < TIMELINE_ROWS; index++) {
            virtualRows.add(new GuideTranscriptVirtualizer.Row(
                    "row-" + index, 10 + index % 13));
        }
        long virtualStarted = System.nanoTime();
        virtualizer.update(virtualRows);
        GuideTranscriptVirtualizer.Window visible =
                virtualizer.visible(240_000, 260, 40);
        long virtualMillis = elapsedMillis(virtualStarted);
        assertTrue(visible.toIndexExclusive() - visible.fromIndex() < 40);
        GuideViewportAnchor anchor = virtualizer.anchorAt(240_000);
        int restored = virtualizer.restore(anchor, 0, 260);
        assertEquals(240_000, restored);
        int changed = visible.fromIndex();
        int priorOffset = virtualizer.offset(changed);
        virtualRows.set(changed, new GuideTranscriptVirtualizer.Row(
                "row-" + changed, virtualRows.get(changed).height() + 7));
        virtualizer.update(virtualRows);
        assertEquals(priorOffset, virtualizer.offset(changed));

        writeEvidence(saveMillis, metadataMillis, pageMillis, contextMillis,
                updateMillis, virtualMillis, page.requests().size(),
                context.messages().size(),
                visible.toIndexExclusive() - visible.fromIndex());
    }

    private static GuideHistoryPartition fixture() {
        SemanticMessageParser parser = new SemanticMessageParser();
        List<SemanticDocument> documents = List.of(
                parser.parse("# Heading\n\nA paragraph with **strong** text."),
                parser.parse("- first\n- second\n\n> grounded summary"),
                parser.parse("| Item | Count |\n|---|---:|\n| Iron | 3 |"),
                parser.parse("```java\nrecipe.lookup();\n```\n\nFinal text."));
        ArrayList<GuideRequestSnapshot> requests = new ArrayList<>(REQUESTS);
        for (int index = 0; index < REQUESTS; index++) {
            SemanticDocument first = documents.get(index % documents.size());
            SemanticDocument last = documents.get((index + 1) % documents.size());
            GuideToolActivity tool = new GuideToolActivity(
                    "call-" + index, 0, "openallay:get_recipe", GuideToolStatus.SUCCEEDED,
                    new JsonObject(), List.of(GuideToolMessage.of(
                            GuideToolMessage.Key.RECIPE_DETAIL,
                            "minecraft:recipe_" + index)), List.of());
            List<GuideTimelineEntry> timeline = List.of(
                    new GuideTimelineEntry.Assistant(
                            0, first.fallbackText(), first, false, List.of()),
                    new GuideTimelineEntry.Tool(1, tool),
                    new GuideTimelineEntry.Assistant(
                            2, last.fallbackText(), last, false, List.of()));
            requests.add(new GuideRequestSnapshot(
                    requestId(index), "main", GuideTopology.CLIENT_LOCAL,
                    "question-" + index, timeline, GuideRequestStatus.COMPLETED,
                    List.of(), ModelUsage.empty(), null, null,
                    NOW.plusSeconds(index), NOW.plusSeconds(index + 1),
                    NOW.plusSeconds(index + 1), GuideModelSelection.client("scale")));
        }
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION, SCOPE, "main",
                List.of(new GuideSessionSnapshot(
                        "main", List.of(), requests, List.of(),
                        GuideModelSelection.client("scale"))),
                NOW.plusSeconds(REQUESTS + 1L));
    }

    private static UUID requestId(int index) {
        return new UUID(1, index + 1L);
    }

    private static long rowId(
            Path database, String table, UUID requestId, Integer ordinal) throws Exception {
        String sql = ordinal == null
                ? "select rowid from requests where scope_id = ? and request_id = ?"
                : "select rowid from timeline_entries where scope_id = ? and request_id = ? and ordinal = ?";
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var query = connection.prepareStatement(sql)) {
            query.setString(1, SCOPE.scopeId());
            query.setString(2, requestId.toString());
            if (ordinal != null) query.setInt(3, ordinal);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static int count(Path database, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var result = connection.createStatement().executeQuery(
                        "select count(*) from " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void writeEvidence(
            long saveMillis,
            long metadataMillis,
            long pageMillis,
            long contextMillis,
            long updateMillis,
            long virtualMillis,
            int pageObjects,
            int contextObjects,
            int visibleRows) throws Exception {
        Path output = Path.of("build/reports/openallay/phase4j-scale.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, """
                {
                  "requestCount": %d,
                  "timelineRowCount": %d,
                  "metadataRequestBodies": 0,
                  "pageRequestObjects": %d,
                  "contextMessageObjects": %d,
                  "visibleVirtualRows": %d,
                  "saveElapsedMillis": %d,
                  "metadataElapsedMillis": %d,
                  "pageElapsedMillis": %d,
                  "contextElapsedMillis": %d,
                  "singleRowUpdateElapsedMillis": %d,
                  "virtualizationElapsedMillis": %d
                }
                """.formatted(
                        REQUESTS, TIMELINE_ROWS, pageObjects, contextObjects, visibleRows,
                        saveMillis, metadataMillis, pageMillis, contextMillis,
                        updateMillis, virtualMillis));
    }
}
