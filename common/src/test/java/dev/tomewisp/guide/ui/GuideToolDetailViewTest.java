package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.tomewisp.guide.GuideToolStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GuideToolDetailViewTest {
    @Test
    void normalViewHasNoDebugProjectionAndCopiesCardsAndNarration() {
        List<GuideItemView> items = new ArrayList<>(List.of(
                new GuideItemView("minecraft:iron_ingot", "Iron Ingot", 4)));
        List<GuideDetailCard> cards = new ArrayList<>(List.of(
                new GuideDetailCard.ItemGrid("screen.tomewisp.detail.inventory", items)));
        List<String> narration = new ArrayList<>(List.of("4 Iron Ingots"));

        GuideToolDetailView view = new GuideToolDetailView(
                "screen.tomewisp.tool.inventory",
                GuideToolStatus.SUCCEEDED,
                cards,
                narration,
                Optional.empty());
        items.clear();
        cards.clear();
        narration.clear();

        assertEquals(1, view.cards().size());
        assertEquals(List.of("4 Iron Ingots"), view.narration());
        assertTrue(view.debug().isEmpty());
    }

    @Test
    void debugProjectionDeepCopiesNormalizedJson() {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        GuideToolDetailView.Debug debug = new GuideToolDetailView.Debug(
                "call-1", "tomewisp:inspect_inventory", List.of(), normalized, "");
        GuideToolDetailView view = new GuideToolDetailView(
                "screen.tomewisp.tool.inventory",
                GuideToolStatus.SUCCEEDED,
                List.of(new GuideDetailCard.Text("screen.tomewisp.detail.result", List.of("ok"))),
                List.of("ok"),
                Optional.of(debug));
        normalized.addProperty("late", "mutation");

        JsonObject first = view.debug().orElseThrow().normalized();
        JsonObject second = view.debug().orElseThrow().normalized();
        assertFalse(first.has("late"));
        assertNotSame(first, second);
        first.addProperty("local", true);
        assertFalse(view.debug().orElseThrow().normalized().has("local"));
    }

    @Test
    void rejectsInvalidCountsAndEmptyCardContent() {
        assertThrows(IllegalArgumentException.class, () ->
                new GuideItemView("minecraft:iron_ingot", "Iron Ingot", -1));
        assertThrows(IllegalArgumentException.class, () ->
                new GuideDetailCard.ItemGrid("screen.tomewisp.detail.inventory", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new GuideDetailCard.Requirement("iron", 4, 5, 0, List.of(), List.of()));
    }
}
