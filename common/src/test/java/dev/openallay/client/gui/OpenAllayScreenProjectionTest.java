package dev.openallay.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.guide.GuideMessage;
import dev.openallay.guide.GuideHistoryPageState;
import dev.openallay.guide.GuideModelMode;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.guide.ui.GuideDisplayConfig;
import dev.openallay.guide.ui.GuideUiRow;
import dev.openallay.guide.ui.GuideUiModelChoice;
import dev.openallay.model.ModelUsage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OpenAllayScreenProjectionTest {
    @Test
    void assistantLabelUsesThePlayerDisplayNameWithoutChangingProductIdentity() {
        GuideDisplayConfig display = new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, false, true, "小羽");

        assertEquals("小羽", OpenAllayScreen.assistantLabel(display, false).getString());
        assertTrue(OpenAllayScreen.assistantLabel(display, true).getString().contains("小羽"));
    }

    @Test
    void contentHighlightRequiresAnExplicitStableFocusIdentity() {
        assertFalse(OpenAllayScreen.isFocused(null, null));
        assertFalse(OpenAllayScreen.isFocused(null, "tool:call-1"));
        assertFalse(OpenAllayScreen.isFocused("tool:call-1", null));
        assertTrue(OpenAllayScreen.isFocused("tool:call-1", "tool:call-1"));
        assertFalse(OpenAllayScreen.isFocused("tool:call-1", "tool:call-2"));
    }

    @Test
    void onlyVisibleChatRowsExposeCopyText() {
        UUID requestId = UUID.fromString("31a2e246-d3d8-41f4-8b3a-814c73dc77ad");
        GuideUiRow.User user = new GuideUiRow.User(requestId, "player text");
        GuideUiRow.Assistant assistant = new GuideUiRow.Assistant(
                requestId,
                0,
                "assistant text",
                new dev.openallay.guide.semantic.SemanticMessageParser().parse("assistant text"),
                false,
                List.of());
        GuideUiRow.Status status = new GuideUiRow.Status(
                requestId, GuideRequestStatus.COMPLETED, "done", null);

        assertEquals("player text", OpenAllayScreen.copyableText(user));
        assertEquals("assistant text", OpenAllayScreen.copyableText(assistant));
        assertNull(OpenAllayScreen.copyableText(status));
    }

    @Test
    void deletionConfirmationTextRetainsTheCapturedSessionTarget() {
        Component first = OpenAllayScreen.deleteConfirmationMessage("session-7", false);
        Component second = OpenAllayScreen.deleteConfirmationMessage("session-7", true);

        TranslatableContents firstTranslation = assertInstanceOf(
                TranslatableContents.class, first.getContents());
        TranslatableContents secondTranslation = assertInstanceOf(
                TranslatableContents.class, second.getContents());
        assertEquals("screen.openallay.session.delete.first.message", firstTranslation.getKey());
        assertEquals("screen.openallay.session.delete.second.message", secondTranslation.getKey());
        assertEquals("session-7", firstTranslation.getArgs()[0]);
        assertEquals("session-7", secondTranslation.getArgs()[0]);
    }

    @Test
    void modelSelectorUsesStableExplicitChoiceIdentities() {
        GuideUiModelChoice client = new GuideUiModelChoice(
                GuideModelSelection.client("profile-1"), "Profile 1", true, true, false);
        GuideUiModelChoice server = new GuideUiModelChoice(
                GuideModelSelection.server(), "Server", true, false, false);

        assertEquals("model:CLIENT:profile-1", OpenAllayScreen.modelFocusId(client));
        assertEquals("model:SERVER:server", OpenAllayScreen.modelFocusId(server));
    }

    @Test
    void streamingRowMeasurementsNeverShrinkAtOneWidth() {
        OpenAllayScreen.StableRowHeights heights = new OpenAllayScreen.StableRowHeights();
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
        assertFalse(OpenAllayScreen.mayPageHistory(
                true, true, GuideHistoryPageState.IDLE, 12));
        assertTrue(OpenAllayScreen.mayPageHistory(
                true, false, GuideHistoryPageState.IDLE, 12));
        assertFalse(OpenAllayScreen.mayPageHistory(
                true, false, GuideHistoryPageState.LOADING, 12));
        assertFalse(OpenAllayScreen.mayPageHistory(
                true, false, GuideHistoryPageState.IDLE, 0));
    }

    @Test
    void composerEnterPolicyMatchesNativeChatExpectations() {
        assertEquals(OpenAllayScreen.ComposerKeyAction.SUBMIT,
                OpenAllayScreen.composerKeyAction(true, true, false, false));
        assertEquals(OpenAllayScreen.ComposerKeyAction.NEWLINE,
                OpenAllayScreen.composerKeyAction(true, true, true, false));
        assertEquals(OpenAllayScreen.ComposerKeyAction.SUBMIT,
                OpenAllayScreen.composerKeyAction(true, true, true, true));
        assertEquals(OpenAllayScreen.ComposerKeyAction.DELEGATE,
                OpenAllayScreen.composerKeyAction(false, true, false, false));
        assertEquals(OpenAllayScreen.ComposerKeyAction.DELEGATE,
                OpenAllayScreen.composerKeyAction(true, false, false, false));
    }

    @Test
    void tickCoalescerAppliesOnlyNewestPendingProjection() {
        OpenAllayScreen.TickCoalescer<String> pending = new OpenAllayScreen.TickCoalescer<>();
        pending.offer("first");
        pending.offer("second");
        pending.offer("latest");

        assertEquals("latest", pending.drain());
        assertNull(pending.drain());
    }

    @Test
    void progressDurationsAreStableAndNeverGoNegative() {
        assertEquals("0:00", OpenAllayScreen.formatDuration(Duration.ofSeconds(-4)));
        assertEquals("1:42", OpenAllayScreen.formatDuration(Duration.ofSeconds(102)));
        assertEquals("2:03:04", OpenAllayScreen.formatDuration(Duration.ofSeconds(7384)));
    }

    @Test
    void collapsedToolSummaryKeepsAtMostThreeSemanticMessages() {
        GuideToolMessage first = GuideToolMessage.of(GuideToolMessage.Key.RESULT_PENDING);
        GuideToolMessage second = GuideToolMessage.of(GuideToolMessage.Key.RESULT_COMPLETED);
        GuideToolMessage third = GuideToolMessage.of(GuideToolMessage.Key.RECIPES_NONE);
        GuideToolMessage fourth = GuideToolMessage.of(GuideToolMessage.Key.CATALOG_PARTIAL);
        assertEquals(
                List.of(first, second, third),
                OpenAllayScreen.visibleToolSummaryMessages(
                        List.of(first, second, third, fourth)));
        assertEquals(51, OpenAllayScreen.toolCardHeight(3));
        assertEquals(21, OpenAllayScreen.toolCardHeight(0));
    }

    @Test
    void toolMessagesUseClosedTranslationKeysAndLiteralArguments() {
        GuideToolMessage message = GuideToolMessage.of(
                GuideToolMessage.Key.RECIPE_DETAIL,
                "minecraft:iron_block");

        Component rendered = OpenAllayScreen.toolMessage(message);
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
                "openallay:inspect_inventory",
                new EvidenceMetadata(
                        DataAuthority.CLIENT_VISIBLE,
                        DataCompleteness.COMPLETE,
                        Instant.EPOCH,
                        "openallay:inventory",
                        "openallay:captured_inventory",
                        "26.2",
                        "fabric",
                        Map.of()));

        String normal = OpenAllayScreen.sourceLabel(source, false);
        assertFalse(normal.contains("CLIENT_VISIBLE"));
        assertFalse(normal.contains("COMPLETE"));
        assertFalse(normal.contains("inventory"));

        String debug = OpenAllayScreen.sourceLabel(source, true);
        assertTrue(debug.contains("CLIENT_VISIBLE"));
        assertTrue(debug.contains("COMPLETE"));
        assertTrue(debug.contains("inventory"));
    }

    @Test
    void liveDisplaySupplierReprojectsSameSnapshotWithoutChangingHistory() {
        GuideToolActivity activity = new GuideToolActivity(
                "call-live",
                0,
                "openallay:inspect_inventory",
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

        GuideUiRow.Tool normal = (GuideUiRow.Tool) OpenAllayScreen
                .project(snapshot, display::get).rows().get(1);
        display.set(new GuideDisplayConfig(GuideDisplayConfig.SCHEMA_VERSION, true, true));
        GuideUiRow.Tool debug = (GuideUiRow.Tool) OpenAllayScreen
                .project(snapshot, display::get).rows().get(1);

        assertTrue(normal.detail().debug().isEmpty());
        assertTrue(debug.detail().debug().isPresent());
        assertSame(request, snapshot.sessions().getFirst().requests().getFirst());
    }
}
