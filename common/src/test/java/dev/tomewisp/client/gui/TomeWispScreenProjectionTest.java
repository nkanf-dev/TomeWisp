package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideHistoryPageState;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.guide.ui.GuideUiModelChoice;
import dev.tomewisp.model.ModelUsage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TomeWispScreenProjectionTest {
    @Test
    void contentHighlightRequiresAnExplicitStableFocusIdentity() {
        assertFalse(TomeWispScreen.isFocused(null, null));
        assertFalse(TomeWispScreen.isFocused(null, "tool:call-1"));
        assertFalse(TomeWispScreen.isFocused("tool:call-1", null));
        assertTrue(TomeWispScreen.isFocused("tool:call-1", "tool:call-1"));
        assertFalse(TomeWispScreen.isFocused("tool:call-1", "tool:call-2"));
    }

    @Test
    void onlyVisibleChatRowsExposeCopyText() {
        UUID requestId = UUID.fromString("31a2e246-d3d8-41f4-8b3a-814c73dc77ad");
        GuideUiRow.User user = new GuideUiRow.User(requestId, "player text");
        GuideUiRow.Assistant assistant = new GuideUiRow.Assistant(
                requestId,
                0,
                "assistant text",
                new dev.tomewisp.guide.semantic.SemanticMessageParser().parse("assistant text"),
                false,
                List.of());
        GuideUiRow.Status status = new GuideUiRow.Status(
                requestId, GuideRequestStatus.COMPLETED, "done", null);

        assertEquals("player text", TomeWispScreen.copyableText(user));
        assertEquals("assistant text", TomeWispScreen.copyableText(assistant));
        assertNull(TomeWispScreen.copyableText(status));
    }

    @Test
    void deletionConfirmationTextRetainsTheCapturedSessionTarget() {
        Component first = TomeWispScreen.deleteConfirmationMessage("session-7", false);
        Component second = TomeWispScreen.deleteConfirmationMessage("session-7", true);

        TranslatableContents firstTranslation = assertInstanceOf(
                TranslatableContents.class, first.getContents());
        TranslatableContents secondTranslation = assertInstanceOf(
                TranslatableContents.class, second.getContents());
        assertEquals("screen.tomewisp.session.delete.first.message", firstTranslation.getKey());
        assertEquals("screen.tomewisp.session.delete.second.message", secondTranslation.getKey());
        assertEquals("session-7", firstTranslation.getArgs()[0]);
        assertEquals("session-7", secondTranslation.getArgs()[0]);
    }

    @Test
    void modelSelectorUsesStableExplicitChoiceIdentities() {
        GuideUiModelChoice client = new GuideUiModelChoice(
                GuideModelSelection.client("profile-1"), "Profile 1", true, true, false);
        GuideUiModelChoice server = new GuideUiModelChoice(
                GuideModelSelection.server(), "Server", true, false, false);

        assertEquals("model:CLIENT:profile-1", TomeWispScreen.modelFocusId(client));
        assertEquals("model:SERVER:server", TomeWispScreen.modelFocusId(server));
    }

    @Test
    void streamingRowMeasurementsNeverShrinkAtOneWidth() {
        TomeWispScreen.StableRowHeights heights = new TomeWispScreen.StableRowHeights();
        heights.begin(300);
        assertEquals(80, heights.retain("assistant:one", 80, true));
        assertEquals(80, heights.retain("assistant:one", 52, true));
        assertEquals(96, heights.retain("assistant:one", 96, true));
        assertEquals(52, heights.retain("assistant:one", 52, false));

        heights.begin(240);
        assertEquals(52, heights.retain("assistant:one", 52, true));
    }

    @Test
    void activeStreamingDefersHistoryPagingThatWouldReorderTheViewport() {
        assertFalse(TomeWispScreen.mayPageHistory(
                true, true, GuideHistoryPageState.IDLE, 12));
        assertTrue(TomeWispScreen.mayPageHistory(
                true, false, GuideHistoryPageState.IDLE, 12));
        assertFalse(TomeWispScreen.mayPageHistory(
                true, false, GuideHistoryPageState.LOADING, 12));
        assertFalse(TomeWispScreen.mayPageHistory(
                true, false, GuideHistoryPageState.IDLE, 0));
    }

    @Test
    void composerEnterPolicyMatchesNativeChatExpectations() {
        assertEquals(TomeWispScreen.ComposerKeyAction.SUBMIT,
                TomeWispScreen.composerKeyAction(true, true, false, false));
        assertEquals(TomeWispScreen.ComposerKeyAction.NEWLINE,
                TomeWispScreen.composerKeyAction(true, true, true, false));
        assertEquals(TomeWispScreen.ComposerKeyAction.SUBMIT,
                TomeWispScreen.composerKeyAction(true, true, true, true));
        assertEquals(TomeWispScreen.ComposerKeyAction.DELEGATE,
                TomeWispScreen.composerKeyAction(false, true, false, false));
        assertEquals(TomeWispScreen.ComposerKeyAction.DELEGATE,
                TomeWispScreen.composerKeyAction(true, false, false, false));
    }

    @Test
    void tickCoalescerAppliesOnlyNewestPendingProjection() {
        TomeWispScreen.TickCoalescer<String> pending = new TomeWispScreen.TickCoalescer<>();
        pending.offer("first");
        pending.offer("second");
        pending.offer("latest");

        assertEquals("latest", pending.drain());
        assertNull(pending.drain());
    }

    @Test
    void progressDurationsAreStableAndNeverGoNegative() {
        assertEquals("0:00", TomeWispScreen.formatDuration(Duration.ofSeconds(-4)));
        assertEquals("1:42", TomeWispScreen.formatDuration(Duration.ofSeconds(102)));
        assertEquals("2:03:04", TomeWispScreen.formatDuration(Duration.ofSeconds(7384)));
    }

    @Test
    void collapsedToolSummaryKeepsAtMostThreeSemanticMessages() {
        GuideToolMessage first = GuideToolMessage.of(GuideToolMessage.Key.RESULT_PENDING);
        GuideToolMessage second = GuideToolMessage.of(GuideToolMessage.Key.RESULT_COMPLETED);
        GuideToolMessage third = GuideToolMessage.of(GuideToolMessage.Key.RECIPES_NONE);
        GuideToolMessage fourth = GuideToolMessage.of(GuideToolMessage.Key.CATALOG_PARTIAL);
        assertEquals(
                List.of(first, second, third),
                TomeWispScreen.visibleToolSummaryMessages(
                        List.of(first, second, third, fourth)));
        assertEquals(51, TomeWispScreen.toolCardHeight(3));
        assertEquals(21, TomeWispScreen.toolCardHeight(0));
    }

    @Test
    void toolMessagesUseClosedTranslationKeysAndLiteralArguments() {
        GuideToolMessage message = GuideToolMessage.of(
                GuideToolMessage.Key.RECIPE_DETAIL,
                "minecraft:iron_block");

        Component rendered = TomeWispScreen.toolMessage(message);
        TranslatableContents translation = assertInstanceOf(
                TranslatableContents.class, rendered.getContents());

        assertEquals(message.key().translationKey(), translation.getKey());
        Component argument = assertInstanceOf(Component.class, translation.getArgs()[0]);
        assertEquals("minecraft:iron_block", argument.getString());
        assertFalse(argument.getContents() instanceof TranslatableContents);
    }

    @Test
    void normalSourceLabelIsFriendlyAndDebugLabelIsTechnical() {
        GuideSource source = new GuideSource(
                "tomewisp:inspect_inventory",
                new EvidenceMetadata(
                        DataAuthority.CLIENT_VISIBLE,
                        DataCompleteness.COMPLETE,
                        Instant.EPOCH,
                        "tomewisp:inventory",
                        "tomewisp:captured_inventory",
                        "26.2",
                        "fabric",
                        Map.of()));

        String normal = TomeWispScreen.sourceLabel(source, false);
        assertFalse(normal.contains("CLIENT_VISIBLE"));
        assertFalse(normal.contains("COMPLETE"));
        assertFalse(normal.contains("inventory"));

        String debug = TomeWispScreen.sourceLabel(source, true);
        assertTrue(debug.contains("CLIENT_VISIBLE"));
        assertTrue(debug.contains("COMPLETE"));
        assertTrue(debug.contains("inventory"));
    }

    @Test
    void liveDisplaySupplierReprojectsSameSnapshotWithoutChangingHistory() {
        GuideToolActivity activity = new GuideToolActivity(
                "call-live",
                0,
                "tomewisp:inspect_inventory",
                GuideToolStatus.SUCCEEDED,
                JsonParser.parseString("""
                        {"status":"success","value":{"counts":{"minecraft:apple":3}}}
                        """).getAsJsonObject(),
                List.of(GuideToolMessage.of(
                        GuideToolMessage.Key.INVENTORY_ITEM,
                        "minecraft:apple",
                        "3")),
                List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                UUID.fromString("d43b1f0c-c527-4284-902e-fab09b799fe0"),
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(new GuideTimelineEntry.Tool(0, activity)),
                GuideRequestStatus.COMPLETED,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH.plusSeconds(1));
        GuideSnapshot snapshot = new GuideSnapshot(
                UUID.fromString("24475b25-61c3-4bd9-aec3-5eafbe6ae283"),
                "main",
                GuideModelMode.CLIENT,
                true,
                false,
                List.of(new GuideSessionSnapshot("main", List.<GuideMessage>of(), List.of(request))),
                Instant.EPOCH.plusSeconds(1));
        AtomicReference<GuideDisplayConfig> display =
                new AtomicReference<>(GuideDisplayConfig.defaults());

        GuideUiRow.Tool normal = (GuideUiRow.Tool) TomeWispScreen
                .project(snapshot, display::get).rows().get(1);
        display.set(new GuideDisplayConfig(GuideDisplayConfig.SCHEMA_VERSION, true, true));
        GuideUiRow.Tool debug = (GuideUiRow.Tool) TomeWispScreen
                .project(snapshot, display::get).rows().get(1);

        assertTrue(normal.detail().debug().isEmpty());
        assertTrue(debug.detail().debug().isPresent());
        assertSame(request, snapshot.sessions().getFirst().requests().getFirst());
    }
}
