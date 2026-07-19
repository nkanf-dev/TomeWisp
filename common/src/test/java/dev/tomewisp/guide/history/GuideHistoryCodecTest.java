package dev.tomewisp.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideHistoryCodecTest {
    private static final UUID ACTOR =
            UUID.fromString("849d783f-aa16-4c7f-ac0f-cd41f073c75f");

    @Test
    void roundTripsStrictCredentialFreeModelSelections() {
        GuideHistoryCodec codec = new GuideHistoryCodec();

        assertEquals(
                GuideModelSelection.client("openrouter-claude"),
                codec.decodeModelSelection(codec.encodeModelSelection(
                        GuideModelSelection.client("openrouter-claude"))));
        assertEquals(
                GuideModelSelection.server(),
                codec.decodeModelSelection(codec.encodeModelSelection(
                        GuideModelSelection.server())));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeModelSelection(
                "{\"kind\":\"CLIENT\",\"profileId\":\"a\",\"apiKey\":\"secret\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeModelSelection(
                "{\"kind\":\"SERVER\",\"profileId\":\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeModelSelection(
                "{\"kind\":\"UNKNOWN\"}"));
    }

    @Test
    void derivesPrivateStablePartitionIdentity() {
        GuideHistoryScope first = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, " Example.COM:25565 ");
        GuideHistoryScope same = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "example.com:25565");
        GuideHistoryScope differentActor = GuideHistoryScope.derive(
                UUID.fromString("bc773dfc-008f-49ec-9313-354734ab9b9b"),
                GuideHistoryScope.Kind.MULTIPLAYER,
                "example.com:25565");
        GuideHistoryScope differentServer = GuideHistoryScope.derive(
                ACTOR, GuideHistoryScope.Kind.MULTIPLAYER, "other.example:25565");

        assertEquals(first, same);
        assertNotEquals(first.scopeId(), differentActor.scopeId());
        assertNotEquals(first.scopeId(), differentServer.scopeId());
        assertEquals(64, first.scopeId().length());
        assertFalse(first.scopeId().contains("example"));
    }

    @Test
    void roundTripsOnlyNormalModeTimelineProjection() {
        GuideHistoryCodec codec = new GuideHistoryCodec();
        JsonObject normalized = new JsonObject();
        normalized.addProperty("secretRawField", "must-not-persist");
        GuideSource source = new GuideSource(
                "tomewisp:get_recipe", GroundedTestFixtures.serverEvidence());
        List<GuideTimelineEntry> timeline = List.of(
                new GuideTimelineEntry.Assistant(0, "I will inspect it.", false, List.of(source)),
                new GuideTimelineEntry.Tool(1, new GuideToolActivity(
                        "call-7",
                        0,
                        "tomewisp:get_recipe",
                        GuideToolStatus.SUCCEEDED,
                        normalized,
                        List.of(
                                GuideToolMessage.of(
                                        GuideToolMessage.Key.RECIPE_DETAIL,
                                        "minecraft:iron_block"),
                                GuideToolMessage.of(
                                        GuideToolMessage.Key.RECIPE_OUTPUT,
                                        "minecraft:iron_block",
                                        "1")),
                        List.of(source))),
                new GuideTimelineEntry.Assistant(2, "It needs nine ingots.", false, List.of()));

        String encoded = codec.encodeTimeline(timeline);
        List<GuideTimelineEntry> restored = codec.decodeTimeline(encoded);

        assertEquals(3, restored.size());
        assertEquals(List.of(0, 1, 2), restored.stream()
                .map(GuideTimelineEntry::ordinal).toList());
        GuideToolActivity tool = ((GuideTimelineEntry.Tool) restored.get(1)).activity();
        assertNull(tool.normalized());
        assertEquals(List.of(
                        GuideToolMessage.of(
                                GuideToolMessage.Key.RECIPE_DETAIL,
                                "minecraft:iron_block"),
                        GuideToolMessage.of(
                                GuideToolMessage.Key.RECIPE_OUTPUT,
                                "minecraft:iron_block",
                                "1")),
                tool.presentationMessages());
        assertEquals(List.of(source), tool.sources());
        assertFalse(encoded.contains("secretRawField"));
        assertFalse(encoded.contains("must-not-persist"));
    }

    @Test
    void rejectsUnknownAndMissingDurableFields() {
        GuideHistoryCodec codec = new GuideHistoryCodec();
        String encoded = codec.encodeTimeline(List.of(
                new GuideTimelineEntry.Assistant(0, "answer", false, List.of())));
        JsonArray unknown = JsonParser.parseString(encoded).getAsJsonArray();
        unknown.get(0).getAsJsonObject().addProperty("unknown", true);

        JsonArray missing = JsonParser.parseString(encoded).getAsJsonArray();
        missing.get(0).getAsJsonObject().remove("text");

        JsonArray fractional = JsonParser.parseString(encoded).getAsJsonArray();
        fractional.get(0).getAsJsonObject().addProperty("ordinal", 0.5);

        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTimeline(unknown.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTimeline(missing.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTimeline(fractional.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTimeline("{}"));
    }

    @Test
    void rejectsUnknownTranslationMessagesInsideDurableToolProjection() {
        GuideHistoryCodec codec = new GuideHistoryCodec();
        String encoded = codec.encodeTimeline(List.of(new GuideTimelineEntry.Tool(
                0,
                new GuideToolActivity(
                        "call-1",
                        0,
                        "tomewisp:get_recipe",
                        GuideToolStatus.SUCCEEDED,
                        null,
                        List.of(GuideToolMessage.of(GuideToolMessage.Key.RESULT_COMPLETED)),
                        List.of()))));
        JsonArray unknownKey = JsonParser.parseString(encoded).getAsJsonArray();
        unknownKey.get(0).getAsJsonObject()
                .getAsJsonArray("presentationMessages")
                .get(0).getAsJsonObject()
                .addProperty("key", "ARBITRARY_TRANSLATION_KEY");

        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTimeline(unknownKey.toString()));
    }
}
