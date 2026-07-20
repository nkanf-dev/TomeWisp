package dev.openallay.client.gui;

import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideFailure;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideService;
import dev.openallay.guide.GuideSource;
import dev.openallay.guide.GuideSubscription;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideHistoryPageState;
import dev.openallay.guide.history.GuideHistoryPageRequest;
import dev.openallay.guide.ui.GuideUiLayout;
import dev.openallay.guide.ui.GuideUiModelChoice;
import dev.openallay.guide.ui.GuideUiProgress;
import dev.openallay.guide.ui.GuideUiRow;
import dev.openallay.guide.ui.GuideUiSession;
import dev.openallay.guide.ui.GuideUiView;
import dev.openallay.guide.ui.GuideDetailCard;
import dev.openallay.guide.ui.GuideDisplayConfig;
import dev.openallay.guide.ui.GuideDisplayRuntime;
import dev.openallay.guide.ui.GuideItemView;
import dev.openallay.guide.ui.GuideRecipeCard;
import dev.openallay.guide.ui.GuideToolDetailView;
import dev.openallay.guide.ui.GuideUiClickRoute;
import dev.openallay.guide.ui.GuideTranscriptVirtualizer;
import dev.openallay.guide.ui.GuideViewportAnchor;
import dev.openallay.guide.ui.SemanticLayout;
import dev.openallay.guide.ui.SemanticLayoutCache;
import dev.openallay.guide.ui.SemanticLayoutEngine;
import dev.openallay.client.gui.nativeview.NativeDomainView;
import dev.openallay.client.gui.nativeview.NativeDomainViewBinding;
import dev.openallay.client.gui.nativeview.NativeDomainViewBindings;
import dev.openallay.client.gui.nativeview.NativeDomainViewRegistry;
import dev.openallay.client.gui.export.GuideSessionExporter;
import dev.openallay.recipe.RecipeNavigationResult;
import dev.openallay.recipe.config.RecipeClientRuntime;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/** Full-screen, non-pausing projection and intent sender for GuideService. */
public final class OpenAllayScreen extends Screen {
    private static final int PANEL = 0xD0181B22;
    private static final int PANEL_ALT = 0xD0242933;
    private static final int ACCENT = 0xFF72D5C4;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFFA9B3BE;
    private static final int ERROR = 0xFFFF7D7D;
    private static final Executor EXPORT_EXECUTOR = command -> Thread.ofVirtual()
            .name("openallay-session-export")
            .start(command);
    private final GuideService service;
    private final RecipeClientRuntime recipeClient;
    private final Supplier<GuideDisplayConfig> display;
    private final Runnable settingsOpener;
    private final TickCoalescer<GuideSnapshot> pendingSnapshots = new TickCoalescer<>();
    private volatile GuideUiView view;
    private GuideSubscription subscription;
    private GuideUiLayout layout;
    private MultiLineEditBox composer;
    private Button send;
    private Button stop;
    private Button retry;
    private Button model;
    private Button export;
    private GuideUiLayout.Rect modelSelectorButton;
    private String draft = "";
    private String notice = "";
    private int scroll;
    private int detailScroll;
    private int detailContentHeight;
    private int activeProgressRenderFrames;
    private boolean sessionOverlay;
    private boolean modelSelectorOpen;
    private boolean exportRunning;
    private int modelSelectorScroll;
    private int modelSelectorCursor;
    private GuideUiRow.Tool selectedTool;
    private GuideSource selectedSource;
    private String selectedSourceFocusId;
    private final List<Hit> hits = new ArrayList<>();
    private final GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
    private final SemanticLayoutCache semanticLayouts = new SemanticLayoutCache();
    private final MinecraftSemanticRenderer semanticRenderer =
            new MinecraftSemanticRenderer(new MinecraftSemanticResolver());
    private NativeDomainViewRegistry nativeViews = new NativeDomainViewRegistry();
    private final Map<String, Integer> semanticHashes = new LinkedHashMap<>();
    private final StableRowHeights stableRowHeights = new StableRowHeights();
    private boolean followBottom = true;
    private String focusedContentId;
    private GuideDisplayConfig projectedDisplay;
    private long presentationTicks;

    public OpenAllayScreen(GuideService service) {
        this(service, RecipeClientRuntime.defaults(), GuideDisplayConfig.defaults());
    }

    public OpenAllayScreen(GuideService service, RecipeClientRuntime recipeClient) {
        this(service, recipeClient, GuideDisplayConfig.defaults());
    }

    public OpenAllayScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig) {
        this(service, recipeClient, displayConfig, null);
    }

    public OpenAllayScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig,
            GuideFailure displayFailure) {
        this(service, recipeClient, displayConfig, displayFailure, null);
    }

    public OpenAllayScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig,
            GuideFailure displayFailure,
            Runnable settingsOpener) {
        this(service, recipeClient, () -> displayConfig, displayFailure, settingsOpener);
    }

    public OpenAllayScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayRuntime display,
            Runnable settingsOpener) {
        this(
                service,
                recipeClient,
                Objects.requireNonNull(display, "display")::config,
                display.failure(),
                settingsOpener);
    }

    private OpenAllayScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            Supplier<GuideDisplayConfig> display,
            GuideFailure displayFailure,
            Runnable settingsOpener) {
        super(Component.translatable("screen.openallay.guide"));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.recipeClient = java.util.Objects.requireNonNull(recipeClient, "recipeClient");
        this.display = java.util.Objects.requireNonNull(display, "display");
        this.settingsOpener = settingsOpener;
        this.projectedDisplay = currentDisplay();
        this.view = GuideUiView.from(service.snapshot(), projectedDisplay);
        List<String> startupNotices = new ArrayList<>();
        recipeClient.failure().ifPresent(failure -> startupNotices.add(Component.translatable(
                "screen.openallay.recipe.invalid_config", failure.code()).getString()));
        if (displayFailure != null) {
            startupNotices.add(Component.translatable(
                    "screen.openallay.debug.invalid_config").getString());
        }
        notice = String.join(" · ", startupNotices);
    }

    @Override
    protected void init() {
        layout = GuideUiLayout.calculate(width, height, detailOpen());
        GuideUiLayout.Rect top = layout.topBar();
        int modelWidth = top.width() < 400 ? 88 : 128;
        int controlsWidth = modelWidth + (settingsOpener == null ? 164 : 188);
        int x = top.x() + top.width() - controlsWidth;
        addRenderableWidget(OpenAllayButton.create(Component.literal("会话"), button -> sessionOverlay = !sessionOverlay)
                .bounds(x, top.y() + 2, 32, 20).build());
        addRenderableWidget(OpenAllayButton.create(Component.literal("+"), button -> createSession())
                .bounds(x + 36, top.y() + 2, 24, 20).build());
        addRenderableWidget(OpenAllayButton.create(Component.literal("×"), button -> closeSession())
                .bounds(x + 64, top.y() + 2, 32, 20).build());
        export = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.action.export"),
                        button -> exportSession())
                .bounds(x + 100, top.y() + 2, 36, 20).build());
        modelSelectorButton = new GuideUiLayout.Rect(x + 140, top.y() + 2, modelWidth, 20);
        model = addRenderableWidget(OpenAllayButton.create(modelLabel(), button -> {
                    modelSelectorOpen = !modelSelectorOpen;
                    if (modelSelectorOpen) revealSelectedModel();
                })
                .bounds(x + 140, top.y() + 2, modelWidth, 20).build());
        addRenderableWidget(OpenAllayButton.create(Component.literal("刷新"), button -> service.refreshCapabilities())
                .bounds(x + 144 + modelWidth, top.y() + 2, 20, 20).build());
        if (settingsOpener != null) {
            addRenderableWidget(OpenAllayButton.create(
                            Component.translatable("screen.openallay.settings.short"),
                            button -> settingsOpener.run())
                    .bounds(x + 168 + modelWidth, top.y() + 2, 20, 20)
                    .build());
        }

        GuideUiLayout.Rect area = layout.composer();
        int actionWidth = 54;
        composer = MultiLineEditBox.builder()
                .setX(area.x()).setY(area.y())
                .setPlaceholder(Component.translatable("screen.openallay.composer.placeholder"))
                .build(font, Math.max(80, area.width() - actionWidth - 6), area.height(),
                        Component.translatable("screen.openallay.composer.narration"));
        composer.setValue(draft, true);
        composer.setValueListener(value -> draft = value);
        addRenderableWidget(composer);
        int actionsX = area.x() + area.width() - actionWidth;
        send = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.action.send"), button -> submit())
                .bounds(actionsX, area.y(), actionWidth, 20).build());
        stop = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.action.stop"), button -> cancel())
                .bounds(actionsX, area.y() + 24, actionWidth, 20).build());
        retry = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.action.retry"), button -> retry())
                .bounds(actionsX, area.y() + 48, actionWidth, 20).build());
        updateVirtualRows(Math.max(40, layout.transcript().width() - 18));
        scroll = followBottom
                ? virtualizer.maximumScroll(transcriptViewportHeight())
                : virtualizer.clampScroll(scroll, transcriptViewportHeight());
        updateControls();
        setInitialFocus(composer);
    }

    @Override
    public void added() {
        // Minecraft may return to this same Screen instance from a native confirmation.
        // A removed screen releases every provider view, so each attachment gets a fresh owner.
        nativeViews = new NativeDomainViewRegistry();
        subscription = service.subscribe(pendingSnapshots::offer);
        // A newly-created Screen is also the return path from settings. Refresh after
        // subscribing so the latest profile/runtime projection cannot be missed.
        service.refreshCapabilities();
    }

    @Override
    public void removed() {
        draft = composer == null ? draft : composer.getValue();
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        nativeViews.close();
    }

    @Override
    protected void repositionElements() {
        GuideViewportAnchor anchor = layout == null ? null : virtualizer.anchorAt(scroll);
        boolean shouldFollow = followBottom;
        draft = composer == null ? draft : composer.getValue();
        rebuildWidgets();
        restoreTranscript(anchor, shouldFollow);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        presentationTicks++;
        nativeViews.tick();
        applyPendingProjection();
        if (layout != null) requestViewportHistory(layout.transcript());
        updateControls();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (modelSelectorOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            modelSelectorOpen = false;
            return true;
        }
        if (modelSelectorOpen
                && (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)) {
            moveModelSelectorCursor(event.key() == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }
        if (modelSelectorOpen && event.isConfirmation()) {
            int choices = view.modelChoices().size();
            if (choices == 0) {
                modelSelectorOpen = false;
            } else {
                modelSelectorCursor = Mth.clamp(modelSelectorCursor, 0, choices - 1);
                selectModel(view.modelChoices().get(modelSelectorCursor));
            }
            return true;
        }
        ComposerKeyAction composerAction = composerKeyAction(
                composer != null && getFocused() == composer,
                event.isConfirmation(),
                event.hasShiftDown(),
                event.hasControlDownWithQuirk());
        if (composerAction == ComposerKeyAction.SUBMIT) {
            submit();
            return true;
        }
        if (composerAction == ComposerKeyAction.NEWLINE) {
            return super.keyPressed(event);
        }
        if (event.key() == GLFW.GLFW_KEY_F6) {
            List<Hit> focusable = hits.stream()
                    .filter(hit -> hit.kind() == HitKind.CONTENT && hit.focusId() != null)
                    .toList();
            if (!focusable.isEmpty()) {
                int current = -1;
                for (int index = 0; index < focusable.size(); index++) {
                    if (focusable.get(index).focusId().equals(focusedContentId)) current = index;
                }
                Hit next = focusable.get((current + 1) % focusable.size());
                focusedContentId = next.focusId();
                clearFocus();
                if (minecraft != null && minecraft.getNarrator().isActive()) {
                    minecraft.getNarrator().saySystemNow(next.narration());
                }
                return true;
            }
        }
        if (event.isConfirmation() && getFocused() != composer && focusedContentId != null) {
            Hit focused = hits.stream()
                    .filter(hit -> focusedContentId.equals(hit.focusId()))
                    .findFirst().orElse(null);
            if (focused != null) {
                focused.action().run();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        GuideUiLayout.Rect modelMenu = modelSelectorBounds();
        if (modelSelectorOpen && modelMenu != null && modelMenu.contains(x, y)) {
            int maximum = Math.max(0, view.modelChoices().size() - visibleModelChoiceCount());
            modelSelectorScroll = Mth.clamp(
                    modelSelectorScroll - (int) Math.signum(scrollY), 0, maximum);
            modelSelectorCursor = Mth.clamp(
                    modelSelectorCursor,
                    modelSelectorScroll,
                    Math.min(view.modelChoices().size() - 1,
                            modelSelectorScroll + visibleModelChoiceCount() - 1));
            return true;
        }
        if (detailOpen() && layout.detail().contains(x, y)) {
            int maximum = Math.max(0, detailContentHeight - layout.detail().height() + 34);
            detailScroll = Mth.clamp(detailScroll - (int) Math.round(scrollY * 24), 0, maximum);
            return true;
        }
        if (layout.transcript().contains(x, y)) {
            int maximum = virtualizer.maximumScroll(Math.max(0, layout.transcript().height() - 14));
            scroll = Mth.clamp(scroll - (int) Math.round(scrollY * 24), 0, maximum);
            followBottom = virtualizer.atBottom(
                    scroll, Math.max(0, layout.transcript().height() - 14));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            if (modelSelectorOpen
                    && (modelSelectorButton == null
                            || !modelSelectorButton.contains(event.x(), event.y()))
                    && (modelSelectorBounds() == null
                            || !modelSelectorBounds().contains(event.x(), event.y()))) {
                modelSelectorOpen = false;
            }
            if (modelSelectorOpen) {
                for (Hit hit : List.copyOf(hits)) {
                    if (hit.kind() == HitKind.MODEL
                            && hit.rect().contains(event.x(), event.y())) {
                        focusedContentId = hit.focusId();
                        clearFocus();
                        hit.action().run();
                        return true;
                    }
                }
            }
            if (detailOpen()) {
                List<Hit> detailHits = hits.stream()
                        .filter(hit -> hit.kind() == HitKind.DETAIL)
                        .toList();
                GuideUiClickRoute route = GuideUiClickRoute.resolveDetail(
                        layout.detail(),
                        detailHits.stream().map(Hit::rect).toList(),
                        event.x(),
                        event.y());
                if (route.kind() == GuideUiClickRoute.Kind.ACTION) {
                    Hit selected = detailHits.get(route.actionIndex());
                    focusedContentId = selected.focusId();
                    clearFocus();
                    selected.action().run();
                    return true;
                }
                if (route.kind() == GuideUiClickRoute.Kind.DISMISS_DETAIL) {
                    selectedTool = null;
                    selectedSource = null;
                    selectedSourceFocusId = null;
                    detailScroll = 0;
                    rebuildForDetail();
                    return true;
                }
            }
            if (sessionOverlay) {
                for (Hit hit : List.copyOf(hits)) {
                    if (hit.kind() == HitKind.SESSION && hit.rect().contains(event.x(), event.y())) {
                        clearFocus();
                        hit.action().run();
                        return true;
                    }
                }
            }
            for (Hit hit : List.copyOf(hits)) {
                if (hit.rect().contains(event.x(), event.y())) {
                    focusedContentId = hit.focusId();
                    clearFocus();
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, width, height, 0xC00B0D12);
        renderTop(graphics);
        renderSessions(graphics);
        renderTranscript(graphics, mouseX, mouseY);
        renderProgress(graphics);
        renderDetail(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, a);
        renderModelSelector(graphics);
    }

    private void renderTop(GuiGraphicsExtractor graphics) {
        GuideUiLayout.Rect top = layout.topBar();
        graphics.fill(top.x(), top.y(), top.x() + top.width(), top.y() + top.height(), PANEL);
        graphics.fill(top.x(), top.y(), top.x() + 3, top.y() + top.height(), ACCENT);
        graphics.fill(top.x() + 3, top.y(), top.x() + 34, top.y() + 2, ACCENT);
        graphics.text(font, Component.translatable("screen.openallay.guide").withStyle(ChatFormatting.BOLD),
                top.x() + 10, top.y() + 6, TEXT);
        graphics.text(font, modelStatus(), top.x() + 10, top.y() + 25, MUTED, false);
        if (!notice.isBlank()) {
            graphics.text(font, notice, top.x() + 122, top.y() + 25, ERROR, false);
        }
    }

    private void renderSessions(GuiGraphicsExtractor graphics) {
        hits.removeIf(hit -> hit.kind() == HitKind.SESSION);
        GuideUiLayout.Rect rail = layout.sessionRail();
        if (rail.width() == 0 && !sessionOverlay) return;
        if (rail.width() == 0) rail = new GuideUiLayout.Rect(12, 40, Math.min(180, width - 24), height - 92);
        graphics.fill(rail.x(), rail.y(), rail.x() + rail.width(), rail.y() + rail.height(), PANEL_ALT);
        graphics.text(font, Component.translatable("screen.openallay.session.title"),
                rail.x() + 8, rail.y() + 8, ACCENT, false);
        int y = rail.y() + 24;
        for (GuideUiSession session : view.sessions()) {
            int color = session.selected() ? 0xFF355F59 : 0xFF20252E;
            graphics.fill(rail.x() + 4, y, rail.x() + rail.width() - 4, y + 20, color);
            if (session.selected()) {
                graphics.fill(rail.x() + 4, y, rail.x() + 7, y + 20, ACCENT);
            }
            String indicator = session.running() ? " ●" : "";
            graphics.text(font, session.id() + indicator, rail.x() + 9, y + 6,
                    session.selected() ? TEXT : MUTED, false);
            String id = session.id();
            hits.add(new Hit(new GuideUiLayout.Rect(rail.x() + 4, y, rail.width() - 8, 20),
                    HitKind.SESSION, () -> {
                        service.selectSession(id);
                        sessionOverlay = false;
                        scroll = 0;
                    }));
            y += 24;
            if (y > rail.y() + rail.height() - 22) break;
        }
    }

    private void renderProgress(GuiGraphicsExtractor graphics) {
        GuideUiProgress progress = view.progress();
        if (progress == null) {
            activeProgressRenderFrames = 0;
            return;
        }
        activeProgressRenderFrames++;
        GuideUiLayout.Rect area = layout.progress();
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), PANEL_ALT);
        graphics.enableScissor(area.x(), area.y(), area.x() + area.width(), area.y() + area.height());
        List<FormattedCharSequence> lines = font.split(
                progressMessage(progress, Instant.now(), projectedDisplay.debugMode()),
                Math.max(1, area.width() - 12));
        for (int index = 0; index < Math.min(2, lines.size()); index++) {
            graphics.text(font, lines.get(index), area.x() + 6, area.y() + 2 + index * 10,
                    progress.phase() == dev.openallay.guide.GuideRequestPhase.ENDPOINT_WAIT
                            ? 0xFFFFD479 : ACCENT,
                    false);
        }
        graphics.disableScissor();
    }

    static Component progressMessage(
            GuideUiProgress progress, Instant now, boolean debugMode) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(now, "now");
        MutableComponent message = Component.translatable(progress.activityTranslationKey());
        message.append(" · ").append(Component.translatable(
                "screen.openallay.progress.elapsed",
                formatDuration(Duration.between(progress.requestStartedAt(), now))));
        if (progress.retryAt() != null) {
            message.append(" · ").append(Component.translatable(
                    "screen.openallay.progress.retry_in",
                    formatDuration(Duration.between(now, progress.retryAt()))));
            if (progress.attempt() > 0) {
                message.append(" · ").append(Component.translatable(
                        "screen.openallay.progress.attempt", progress.attempt()));
            }
        } else if (progress.deadlineAt() != null) {
            message.append(" · ").append(Component.translatable(
                    "screen.openallay.progress.remaining",
                    formatDuration(Duration.between(now, progress.deadlineAt()))));
        } else if (progress.phase()
                == dev.openallay.guide.GuideRequestPhase.RESPONSE_STREAMING) {
            message.append(" · ").append(Component.translatable(
                    "screen.openallay.progress.last_update",
                    formatDuration(Duration.between(progress.lastProgressAt(), now))));
        }
        if (debugMode && progress.retryAt() == null && progress.attempt() > 0) {
            message.append(" · ").append(Component.translatable(
                    "screen.openallay.progress.attempt", progress.attempt()));
        }
        return message;
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, remainder)
                : "%d:%02d".formatted(minutes, remainder);
    }

    private void renderTranscript(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        hits.removeIf(hit -> hit.kind() == HitKind.CONTENT);
        GuideUiLayout.Rect area = layout.transcript();
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), PANEL);
        graphics.enableScissor(area.x(), area.y(), area.x() + area.width(), area.y() + area.height());
        int textWidth = Math.max(40, area.width() - 18);
        int viewportHeight = Math.max(0, area.height() - 14);
        GuideTranscriptVirtualizer.Window window = virtualizer.visible(scroll, viewportHeight, 30);
        int contentTop = area.y() + 7;
        if (view.rows().isEmpty()) {
            graphics.text(font, Component.translatable(
                            "screen.openallay.transcript.empty",
                            projectedDisplay.assistantName()),
                    area.x() + 9, contentTop, MUTED, false);
        }
        nativeViews.beginFrame();
        try {
            for (int index = window.fromIndex(); index < window.toIndexExclusive(); index++) {
                int y = contentTop - scroll + virtualizer.offset(index);
                renderRow(
                        graphics, view.rows().get(index), area.x() + 9, y,
                        textWidth, mouseX, mouseY);
            }
        } finally {
            nativeViews.endFrame();
        }
        hits.removeIf(hit -> hit.kind() == HitKind.CONTENT && !intersects(hit.rect(), area));
        hits.stream()
                .filter(hit -> hit.kind() == HitKind.CONTENT
                        && isFocused(focusedContentId, hit.focusId()))
                .findFirst()
                .ifPresent(hit -> graphics.outline(
                        hit.rect().x(), hit.rect().y(), hit.rect().width(), hit.rect().height(),
                        0xFFFFFFFF));
        graphics.disableScissor();
    }

    private int renderRow(
            GuiGraphicsExtractor graphics,
            GuideUiRow row,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY) {
        if (row instanceof GuideUiRow.User user) {
            graphics.text(font, Component.translatable("screen.openallay.speaker.user"),
                    x, y, ACCENT, false);
            renderCopyAction(graphics, row, user.text(), x, y, width);
            y += 11;
            y = renderWrapped(graphics, GuideMarkup.paragraphs(user.text()), x + 6, y, width - 6, TEXT);
            return y + 8;
        }
        if (row instanceof GuideUiRow.Assistant assistant) {
            graphics.fill(x, y, x + 2, y + 9, ACCENT);
            graphics.text(font, assistantLabel(projectedDisplay, assistant.streaming()),
                    x + 6, y, ACCENT, false);
            renderCopyAction(graphics, row, assistant.text(), x, y, width);
            y += 11;
            if (assistant.text().isBlank()) {
                graphics.text(font, Component.translatable(
                                "screen.openallay.assistant.preparing"),
                        x + 6, y, MUTED, false);
                y += 10;
            } else {
                SemanticLayout semantic = semanticLayout(assistant, width - 6);
                MinecraftSemanticRenderer.Result rendered = semanticRenderer.render(
                        graphics, font, semantic, x + 6, y, width - 6,
                        mouseX, mouseY, projectedDisplay.animationsEnabled(),
                        presentationTicks,
                        (nativeGraphics, nativeFont, component, bounds,
                                nativeMouseX, nativeMouseY, ticks) -> renderNativeRecipe(
                                        assistant,
                                        nativeGraphics,
                                        nativeFont,
                                        component,
                                        bounds,
                                        nativeMouseX,
                                        nativeMouseY,
                                        ticks));
                for (MinecraftSemanticRenderer.Hit hit : rendered.hits()) {
                    String focusId = "semantic:" + rowId(assistant) + ":" + hit.intent();
                    hits.add(new Hit(
                            hit.bounds(), HitKind.CONTENT,
                            () -> semanticIntent(hit.intent()), focusId,
                            semanticIntentNarration(hit.intent())));
                }
                y = rendered.bottom();
            }
            for (int sourceIndex = 0; sourceIndex < assistant.sources().size(); sourceIndex++) {
                GuideSource source = assistant.sources().get(sourceIndex);
                int sourceY = y;
                String label = sourceLabel(source, projectedDisplay.debugMode());
                String sourceFocusId = sourceFocusId(assistant, source, sourceIndex);
                boolean selected = isFocused(selectedSourceFocusId, sourceFocusId);
                if (selected) {
                    graphics.fill(x + 4, sourceY - 2, x + width - 4, sourceY + 10, 0xFF31453F);
                }
                graphics.text(font, label, x + 6, sourceY, ACCENT, false);
                hits.add(new Hit(new GuideUiLayout.Rect(x + 4, sourceY - 2, width - 8, 12),
                        HitKind.CONTENT, () -> open(source, sourceFocusId),
                        sourceFocusId, label));
                y += 12;
            }
            return y + 8;
        }
        if (row instanceof GuideUiRow.Tool tool) {
            GuideToolActivity activity = tool.activity();
            List<FormattedCharSequence> summaries = toolSummaryLines(activity, width - 14);
            int rowHeight = toolCardHeight(summaries.size());
            int color = activity.status() == GuideToolStatus.FAILED ? ERROR : 0xFF7FC8A9;
            String icon = switch (activity.status()) {
                case RUNNING -> "◌";
                case SUCCEEDED -> "✓";
                case FAILED -> "!";
            };
            boolean selected = selectedTool != null
                    && toolFocusId(selectedTool).equals(toolFocusId(tool));
            graphics.fill(x + 2, y - 2, x + width - 2, y + rowHeight - 6,
                    selected ? 0xFF31453F : PANEL_ALT);
            if (selected) {
                graphics.outline(x + 2, y - 2, width - 4, rowHeight - 4, ACCENT);
            }
            graphics.text(font, Component.literal(icon + " ").append(friendlyTool(activity.toolId())),
                    x + 7, y + 2, color, false);
            int summaryY = y + 14;
            for (FormattedCharSequence summary : summaries) {
                graphics.text(font, summary, x + 9, summaryY, MUTED, false);
                summaryY += 10;
            }
            hits.add(new Hit(new GuideUiLayout.Rect(
                            x + 2, y - 2, width - 4, rowHeight - 4),
                    HitKind.CONTENT, () -> open(tool), toolFocusId(tool),
                    friendlyTool(activity.toolId()).getString()));
            return y + rowHeight;
        }
        if (row instanceof GuideUiRow.Persistence persistence) {
            int color = persistence.state()
                            == dev.openallay.guide.GuidePersistenceSnapshot.State.UNAVAILABLE
                    ? 0xFFFFD479
                    : MUTED;
            Component message = Component.translatable(persistence.translationKey());
            if (persistence.failure() != null) {
                message = message.copy().append(" (" + persistence.failure().code() + ")");
            }
            graphics.text(font, message, x + 6, y, color, false);
            return y + 18;
        }
        GuideUiRow.Status status = (GuideUiRow.Status) row;
        int color = status.status() == GuideRequestStatus.RATE_LIMITED ? 0xFFFFD479 : ERROR;
        Component message = status.status() == GuideRequestStatus.INTERRUPTED
                ? Component.translatable("screen.openallay.history.interrupted")
                : Component.literal(status.text());
        graphics.text(font, message, x + 6, y, color, false);
        return y + 18;
    }

    private void updateVirtualRows(int width) {
        ArrayList<GuideTranscriptVirtualizer.Row> measured = new ArrayList<>();
        Map<String, Integer> nextHashes = new LinkedHashMap<>();
        HashSet<String> retainedIds = new HashSet<>();
        stableRowHeights.begin(width);
        for (GuideUiRow row : view.rows()) {
            String id = rowId(row);
            retainedIds.add(id);
            if (row instanceof GuideUiRow.Assistant assistant) {
                int hash = assistant.semantic().hashCode();
                nextHashes.put(id, hash);
                if (!java.util.Objects.equals(semanticHashes.get(id), hash)) {
                    semanticLayouts.invalidateRow(id);
                }
            }
            boolean stabilize = row instanceof GuideUiRow.Assistant assistant
                    && assistant.streaming();
            measured.add(new GuideTranscriptVirtualizer.Row(
                    id, stableRowHeights.retain(id, measureRow(row, width), stabilize)));
        }
        stableRowHeights.retainOnly(retainedIds);
        semanticHashes.clear();
        semanticHashes.putAll(nextHashes);
        virtualizer.update(measured);
    }

    private int measureRow(GuideUiRow row, int width) {
        if (row instanceof GuideUiRow.User user) {
            return 11 + wrappedHeight(GuideMarkup.paragraphs(user.text()), width - 6) + 8;
        }
        if (row instanceof GuideUiRow.Assistant assistant) {
            int body = assistant.text().isBlank()
                    ? 10 : semanticLayout(assistant, width - 6).height();
            return 11 + body + assistant.sources().size() * 12 + 8;
        }
        if (row instanceof GuideUiRow.Tool tool) {
            return toolCardHeight(toolSummaryLines(tool.activity(), width - 14).size());
        }
        return 18;
    }

    private List<FormattedCharSequence> toolSummaryLines(
            GuideToolActivity activity, int width) {
        ArrayList<FormattedCharSequence> result = new ArrayList<>();
        for (GuideToolMessage message : visibleToolSummaryMessages(
                activity.presentationMessages())) {
            for (FormattedCharSequence wrapped : font.split(
                    toolMessage(message), Math.max(1, width))) {
                result.add(wrapped);
                if (result.size() == 3) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    static List<GuideToolMessage> visibleToolSummaryMessages(
            List<GuideToolMessage> messages) {
        return messages.stream()
                .limit(3)
                .toList();
    }

    static Component toolMessage(GuideToolMessage message) {
        Object[] arguments = message.arguments().stream()
                .map(Component::literal)
                .toArray();
        return Component.translatable(message.key().translationKey(), arguments);
    }

    static int toolCardHeight(int visibleSummaryLines) {
        if (visibleSummaryLines < 0 || visibleSummaryLines > 3) {
            throw new IllegalArgumentException("visible Tool summary line count must be 0..3");
        }
        return 21 + visibleSummaryLines * 10;
    }

    private int wrappedHeight(List<Component> paragraphs, int width) {
        int height = 0;
        for (Component paragraph : paragraphs) {
            List<FormattedCharSequence> lines = font.split(paragraph, Math.max(1, width));
            height += Math.max(1, lines.size()) * 10;
        }
        return height;
    }

    private SemanticLayout semanticLayout(GuideUiRow.Assistant assistant, int width) {
        return semanticLayouts.get(
                rowId(assistant), assistant.semantic(), Math.max(1, width),
                java.util.Locale.getDefault().toLanguageTag(), font.getClass().getName(),
                new SemanticLayoutEngine.Measurer() {
                    @Override public int width(String text, SemanticLayout.Style style) {
                        return font.width(Component.literal(text).withStyle(switch (style) {
                            case EMPHASIS -> ChatFormatting.ITALIC;
                            case STRONG -> ChatFormatting.BOLD;
                            case CODE -> ChatFormatting.GRAY;
                            case REFERENCE -> ChatFormatting.AQUA;
                            case NORMAL -> ChatFormatting.WHITE;
                        }));
                    }
                    @Override public int lineHeight(SemanticLayout.Kind kind) {
                        return kind == SemanticLayout.Kind.HEADING ? 12 : 10;
                    }
                });
    }

    private static String rowId(GuideUiRow row) {
        return switch (row) {
            case GuideUiRow.Persistence value -> "persistence:" + value.state();
            case GuideUiRow.User value -> "user:" + value.requestId();
            case GuideUiRow.Assistant value ->
                    "assistant:" + value.requestId() + ":" + value.ordinal();
            case GuideUiRow.Tool value ->
                    "tool:" + value.requestId() + ":" + value.activity().invocationId();
            case GuideUiRow.Status value -> "status:" + value.requestId();
        };
    }

    private void requestViewportHistory(GuideUiLayout.Rect area) {
        GuideSessionSnapshot session = service.snapshot().sessions().stream()
                .filter(value -> value.sessionId().equals(view.selectedSession()))
                .findFirst().orElse(null);
        // Paging mutates the head of the transcript. Defer every paging direction while a
        // request is active so streaming deltas cannot race a history replacement and move the
        // visible conversation between the top and bottom of the viewport.
        if (!mayPageHistory(
                session != null,
                view.progress() != null,
                session == null ? null : session.historyWindow().state(),
                session == null ? 0 : session.historyWindow().totalRequests())) return;
        int count = Math.max(1, area.height() / 20 * 2);
        if (session.requests().isEmpty()) {
            service.requestHistoryWindow(
                    session.sessionId(), GuideHistoryPageRequest.Direction.NEWEST, null, count);
        } else if (scroll <= 32 && session.historyWindow().hasEarlier()
                && session.historyWindow().firstLoaded() != null) {
            service.requestHistoryWindow(
                    session.sessionId(), GuideHistoryPageRequest.Direction.BEFORE,
                    session.historyWindow().firstLoaded(), count);
        }
    }

    static boolean mayPageHistory(
            boolean sessionPresent,
            boolean requestRunning,
            GuideHistoryPageState state,
            long totalRequests) {
        return sessionPresent
                && !requestRunning
                && state == GuideHistoryPageState.IDLE
                && totalRequests > 0;
    }

    private void semanticIntent(MinecraftSemanticRenderer.Intent intent) {
        switch (intent) {
            case MinecraftSemanticRenderer.Intent.BrowseRecipes value ->
                    navigate(recipeClient.openRecipes(value.itemId()));
            case MinecraftSemanticRenderer.Intent.BrowseUsages value ->
                    navigate(recipeClient.openUsages(value.itemId()));
            case MinecraftSemanticRenderer.Intent.ExactRecipe value ->
                    navigate(recipeClient.openExact(value.reference()));
            case MinecraftSemanticRenderer.Intent.Source value -> openSemanticSource(
                    value.sourceId(), value.originInvocationId());
            case MinecraftSemanticRenderer.Intent.Evidence value -> openSemanticSource(
                    value.evidenceId(), value.originInvocationId());
            case MinecraftSemanticRenderer.Intent.Choice value -> notice =
                    "选择：" + value.choiceId();
        }
    }

    private static String semanticIntentNarration(MinecraftSemanticRenderer.Intent intent) {
        return switch (intent) {
            case MinecraftSemanticRenderer.Intent.BrowseRecipes value ->
                    "查看 " + value.itemId() + " 的配方";
            case MinecraftSemanticRenderer.Intent.BrowseUsages value ->
                    "查看 " + value.itemId() + " 的用途";
            case MinecraftSemanticRenderer.Intent.ExactRecipe value ->
                    "打开配方 " + value.reference().recipeId();
            case MinecraftSemanticRenderer.Intent.Source value ->
                    "查看来源 " + value.sourceId();
            case MinecraftSemanticRenderer.Intent.Evidence value ->
                    "查看证据 " + value.evidenceId();
            case MinecraftSemanticRenderer.Intent.Choice value ->
                    "选择 " + value.choiceId();
        };
    }

    private static boolean intersects(GuideUiLayout.Rect first, GuideUiLayout.Rect second) {
        return first.x() < second.x() + second.width()
                && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height()
                && first.y() + first.height() > second.y();
    }

    private void openSemanticSource(String sourceId, String invocationId) {
        GuideSource source = view.rows().stream()
                .flatMap(row -> switch (row) {
                    case GuideUiRow.Assistant assistant -> assistant.sources().stream();
                    case GuideUiRow.Tool tool -> tool.activity().invocationId().equals(invocationId)
                            ? tool.activity().sources().stream() : java.util.stream.Stream.empty();
                    default -> java.util.stream.Stream.empty();
                })
                .filter(value -> value.evidence().sourceId().equals(sourceId))
                .findFirst().orElse(null);
        if (source != null) open(source);
    }

    private int renderWrapped(
            GuiGraphicsExtractor graphics, List<Component> paragraphs, int x, int y, int width, int color) {
        for (Component paragraph : paragraphs) {
            List<FormattedCharSequence> lines = font.split(paragraph, width);
            if (lines.isEmpty()) y += 9;
            for (FormattedCharSequence line : lines) {
                if (y >= layout.transcript().y() - 12
                        && y <= layout.transcript().y() + layout.transcript().height() + 12) {
                    graphics.text(font, line, x, y, color, false);
                }
                y += 10;
            }
        }
        return y;
    }

    static Component assistantLabel(GuideDisplayConfig display, boolean streaming) {
        Objects.requireNonNull(display, "display");
        return streaming
                ? Component.literal(display.assistantName())
                        .append(" · ")
                        .append(Component.translatable(
                                "screen.openallay.speaker.assistant_thinking"))
                : Component.literal(display.assistantName());
    }

    private void renderDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!detailOpen()) return;
        hits.removeIf(hit -> hit.kind() == HitKind.DETAIL);
        GuideUiLayout.Rect detail = layout.detail();
        graphics.fill(detail.x(), detail.y(), detail.x() + detail.width(), detail.y() + detail.height(), 0xF02A303A);
        graphics.outline(detail.x(), detail.y(), detail.width(), detail.height(), ACCENT);
        graphics.text(font, Component.translatable("screen.openallay.detail.title"),
                detail.x() + 8, detail.y() + 8, ACCENT, false);
        graphics.enableScissor(
                detail.x() + 1,
                detail.y() + 21,
                detail.x() + detail.width() - 1,
                detail.y() + detail.height() - 1);
        int y = detail.y() + 26 - detailScroll;
        if (selectedTool != null) {
            GuideToolDetailView toolDetail = selectedTool.detail();
            graphics.text(font, Component.translatable(toolDetail.titleKey()),
                    detail.x() + 8, y, TEXT, false);
            y += 16;
            for (GuideDetailCard card : toolDetail.cards()) {
                y = detailCard(graphics, card, detail, y, mouseX, mouseY);
            }
            if (toolDetail.cards().isEmpty()) {
                for (GuideToolMessage message : toolDetail.narration()) {
                    y = detailLine(graphics, toolMessage(message), detail, y);
                }
            }
            if (toolDetail.debug().isPresent()) {
                GuideToolDetailView.Debug debug = toolDetail.debug().orElseThrow();
                y = detailLine(graphics,
                        Component.translatable("screen.openallay.debug.section").getString(), detail, y + 4);
                y = detailLine(graphics, "invocationId: " + debug.invocationId(), detail, y);
                y = detailLine(graphics, "toolId: " + debug.toolId(), detail, y);
                if (debug.uiReference().resultPath() != null) {
                    y = detailLine(graphics,
                            "resultPath: " + debug.uiReference().resultPath(), detail, y);
                }
                y = detailLine(graphics,
                        "resources: " + debug.uiReference().primaryResources().size()
                                + " · presentation: " + debug.uiReference().presentationKind()
                                + " · continuation: " + debug.uiReference().continuationAvailable(),
                        detail,
                        y);
                y = detailLine(graphics,
                        "exact/model: " + debug.diagnostics().normalizedBytes() + " B / "
                                + debug.diagnostics().modelCharacters() + " chars",
                        detail,
                        y);
                y = detailLine(graphics,
                        "generation: " + debug.diagnostics().generationId(), detail, y);
                y = detailLine(graphics,
                        "projectedAt: " + debug.diagnostics().projectedAt(), detail, y);
                if (!debug.validationDiagnostic().isBlank()) {
                    y = detailLine(graphics,
                            "validation: " + debug.validationDiagnostic(), detail, y);
                }
                for (GuideSource source : debug.sources()) {
                    y = evidence(graphics, source, detail, y);
                }
                if (debug.normalized() != null) {
                    y = detailLine(graphics, "boundedSummary: " + debug.normalized(), detail, y);
                }
            }
        } else if (selectedSource != null) {
            if (projectedDisplay.debugMode()) {
                y = evidence(graphics, selectedSource, detail, y);
            } else {
                y = detailLine(graphics,
                        Component.translatable("screen.openallay.detail.source_friendly").getString(),
                        detail,
                        y);
            }
        }
        detailContentHeight = Math.max(0, y + detailScroll - detail.y());
        graphics.disableScissor();
    }

    private int detailCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard card,
            GuideUiLayout.Rect detail,
            int y,
            int mouseX,
            int mouseY) {
        return switch (card) {
            case GuideDetailCard.Recipe recipe ->
                    recipeCard(graphics, recipe.recipe(), detail, y, mouseX, mouseY);
            case GuideDetailCard.ItemGrid grid ->
                    itemGridCard(graphics, grid, detail, y, mouseX, mouseY);
            case GuideDetailCard.Requirements requirements ->
                    requirementsCard(graphics, requirements, detail, y, mouseX, mouseY);
            case GuideDetailCard.ResourceSummary resource ->
                    resourceSummaryCard(graphics, resource, detail, y);
            case GuideDetailCard.Text text -> textCard(graphics, text, detail, y);
            case GuideDetailCard.Error error -> errorCard(graphics, error, detail, y);
        };
    }

    private int resourceSummaryCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard.ResourceSummary card,
            GuideUiLayout.Rect detail,
            int y) {
        int start = y;
        y = detailLine(graphics,
                Component.translatable("screen.openallay.detail.resource").getString(), detail, y);
        y = detailLine(graphics,
                Component.translatable(
                        "screen.openallay.detail.resource_summary",
                        card.operation(), card.succeeded(), card.failed()).getString(),
                detail, y);
        if (!card.firstRequestedPath().isBlank()) {
            Component requested = card.additionalRequestedPaths() == 0
                    ? Component.translatable(
                            "screen.openallay.detail.resource_requested_one",
                            card.firstRequestedPath())
                    : Component.translatable(
                            "screen.openallay.detail.resource_requested",
                            card.firstRequestedPath(), card.additionalRequestedPaths());
            y = detailLine(graphics,
                    requested.getString(),
                    detail, y);
        }
        if (!card.kinds().isEmpty()) {
            y = detailLine(graphics,
                    Component.translatable(
                            "screen.openallay.detail.resource_kinds",
                            String.join(", ", card.kinds())).getString(),
                    detail, y);
        }
        if (!card.resultPath().isBlank()) {
            y = detailLine(graphics,
                    Component.translatable(
                            "screen.openallay.detail.resource_result", card.resultPath()).getString(),
                    detail, y);
        }
        if (card.continuationAvailable()) {
            y = detailLine(graphics,
                    Component.translatable(
                            "screen.openallay.detail.resource_continuation").getString(),
                    detail, y);
        }
        return Math.max(y, start + 25);
    }

    private int itemGridCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard.ItemGrid card,
            GuideUiLayout.Rect detail,
            int y,
            int mouseX,
            int mouseY) {
        int columns = Math.max(1, (detail.width() - 24) / 22);
        int rows = (card.items().size() + columns - 1) / columns;
        int height = 22 + rows * 22;
        int left = detail.x() + 6;
        if (visibleDetail(y, height, detail)) {
            graphics.fill(left, y, detail.x() + detail.width() - 6, y + height, PANEL_ALT);
            graphics.text(font, Component.translatable(card.titleKey()), left + 7, y + 6, TEXT, false);
            for (int index = 0; index < card.items().size(); index++) {
                int itemX = left + 7 + (index % columns) * 22;
                int itemY = y + 19 + (index / columns) * 22;
                renderItem(graphics, card.items().get(index), itemX, itemY, mouseX, mouseY);
            }
        }
        return y + height + 5;
    }

    private int requirementsCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard.Requirements card,
            GuideUiLayout.Rect detail,
            int y,
            int mouseX,
            int mouseY) {
        int height = 31 + Math.max(1, card.requirements().size()) * 31;
        int left = detail.x() + 6;
        if (visibleDetail(y, height, detail)) {
            graphics.fill(left, y, detail.x() + detail.width() - 6, y + height, PANEL_ALT);
            String state = card.craftable()
                    ? Component.translatable("screen.openallay.craftability.ready").getString()
                    : Component.translatable("screen.openallay.craftability.missing").getString();
            graphics.text(font, state, left + 7, y + 6, card.craftable() ? 0xFF7FC8A9 : 0xFFFFD479, false);
            graphics.text(font,
                    Component.translatable(
                            "screen.openallay.craftability.maximum", card.maximumCrafts()),
                    left + 7,
                    y + 17,
                    MUTED,
                    false);
            int rowY = y + 31;
            for (GuideDetailCard.Requirement requirement : card.requirements()) {
                graphics.text(font,
                        requirement.key() + "  " + requirement.allocated() + "/" + requirement.required(),
                        left + 7,
                        rowY + 2,
                        requirement.missing() == 0 ? TEXT : 0xFFFFD479,
                        false);
                int itemX = left + 7;
                for (GuideItemView item : requirement.allocatedItems()) {
                    renderItem(graphics, item, itemX, rowY + 12, mouseX, mouseY);
                    itemX += 20;
                }
                if (requirement.missing() > 0) {
                    graphics.text(font,
                            Component.translatable(
                                    "screen.openallay.craftability.need", requirement.missing()),
                            Math.max(itemX + 3, left + 98),
                            rowY + 17,
                            ERROR,
                            false);
                }
                rowY += 31;
            }
        }
        return y + height + 5;
    }

    private int textCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard.Text card,
            GuideUiLayout.Rect detail,
            int y) {
        int start = y;
        y = detailLine(graphics, Component.translatable(card.titleKey()).getString(), detail, y);
        for (String line : card.lines()) y = detailLine(graphics, line, detail, y);
        return Math.max(y, start + 25);
    }

    private int errorCard(
            GuiGraphicsExtractor graphics,
            GuideDetailCard.Error card,
            GuideUiLayout.Rect detail,
            int y) {
        return detailLine(graphics, card.message(), detail, y);
    }

    private int recipeCard(
            GuiGraphicsExtractor graphics,
            GuideRecipeCard card,
            GuideUiLayout.Rect detail,
            int y,
            int mouseX,
            int mouseY) {
        boolean canBrowse = recipeClient.canBrowse();
        int ingredientCount = card.ingredients().size() + card.catalysts().size();
        int materialRows = ingredientCount == 0 ? 0 : (ingredientCount + 7) / 8;
        int byproductRows = card.byproducts().isEmpty() ? 0 : (card.byproducts().size() + 7) / 8;
        int height = (canBrowse ? 62 : 74) + materialRows * 23 + byproductRows * 23;
        if (visibleDetail(y, height, detail)) {
            int left = detail.x() + 6;
            int right = detail.x() + detail.width() - 6;
            graphics.fill(left, y, right, y + height, PANEL_ALT);
            graphics.outline(left, y, right - left, height, 0xFF46515F);
            GuideRecipeCard.Output output = card.outputs().getFirst();
            ItemStack stack = itemStack(output);
            if (!stack.isEmpty()) {
                graphics.item(stack, left + 7, y + 7);
                graphics.itemDecorations(font, stack, left + 7, y + 7);
                if (mouseX >= left + 7 && mouseX < left + 23
                        && mouseY >= y + 7 && mouseY < y + 23) {
                    graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            }
            graphics.text(font, output.displayName(), left + 29, y + 7, TEXT, false);
            if (!card.workstation().isBlank()) {
                graphics.text(font,
                        Component.translatable("screen.openallay.recipe.workstation", card.workstation()),
                        left + 29,
                        y + 20,
                        MUTED,
                        false);
            }
            int rowY = y + 34;
            int materialIndex = 0;
            for (GuideRecipeCard.Ingredient ingredient : card.ingredients()) {
                GuideItemView item = ingredientItem(ingredient);
                renderItem(graphics, item, left + 7 + (materialIndex % 8) * 22,
                        rowY + (materialIndex / 8) * 23, mouseX, mouseY);
                materialIndex++;
            }
            for (GuideRecipeCard.Ingredient catalyst : card.catalysts()) {
                GuideItemView item = ingredientItem(catalyst);
                renderItem(graphics, item, left + 7 + (materialIndex % 8) * 22,
                        rowY + (materialIndex / 8) * 23, mouseX, mouseY);
                materialIndex++;
            }
            rowY += materialRows * 23;
            for (int index = 0; index < card.byproducts().size(); index++) {
                GuideRecipeCard.Output outputView = card.byproducts().get(index);
                renderItem(graphics,
                        new GuideItemView(outputView.itemId(), outputView.displayName(), outputView.count()),
                        left + 7 + (index % 8) * 22,
                        rowY + (index / 8) * 23,
                        mouseX,
                        mouseY);
            }
            int actionY = y + 44 + materialRows * 23 + byproductRows * 23;
            int actionX = left + 7;
            actionX = recipeAction(
                    graphics,
                    Component.translatable("screen.openallay.recipe.recipes"),
                    actionX,
                    actionY,
                    canBrowse,
                    () -> navigate(recipeClient.openRecipes(output.itemId())));
            actionX = recipeAction(
                    graphics,
                    Component.translatable("screen.openallay.recipe.usages"),
                    actionX + 7,
                    actionY,
                    canBrowse,
                    () -> navigate(recipeClient.openUsages(output.itemId())));
            var exact = card.references().stream().filter(recipeClient::supportsExact).findFirst();
            recipeAction(
                    graphics,
                    Component.translatable(exact.isPresent()
                            ? "screen.openallay.recipe.open_exact"
                            : "screen.openallay.recipe.open_exact_unavailable"),
                    actionX + 7,
                    actionY,
                    exact.isPresent(),
                    () -> navigate(recipeClient.openExact(exact.orElseThrow())));
            if (!canBrowse) {
                graphics.text(
                        font,
                        Component.translatable("screen.openallay.recipe.viewer_unavailable"),
                        left + 7,
                        actionY + 14,
                        0xFFFFD479,
                        false);
            }
        }
        return y + height + 5;
    }

    private int recipeAction(
            GuiGraphicsExtractor graphics,
            Component label,
            int x,
            int y,
            boolean enabled,
            Runnable action) {
        int width = font.width(label) + 8;
        int color = enabled ? ACCENT : 0xFF6E7782;
        graphics.fill(x, y - 2, x + width, y + 10, enabled ? 0xFF29443F : 0xFF30343A);
        graphics.text(font, label, x + 4, y, color, false);
        if (enabled) {
            hits.add(new Hit(
                    new GuideUiLayout.Rect(x, y - 2, width, 12),
                    HitKind.DETAIL,
                    action));
        }
        return x + width;
    }

    private static ItemStack itemStack(GuideRecipeCard.Output output) {
        return itemStack(output.itemId(), output.count());
    }

    private static ItemStack itemStack(String itemId, long count) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id),
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, count)));
    }

    private static GuideItemView ingredientItem(GuideRecipeCard.Ingredient ingredient) {
        GuideRecipeCard.Alternative alternative = ingredient.alternatives().getFirst();
        String itemId = alternative.resolvedItems().isEmpty()
                ? alternative.id()
                : alternative.resolvedItems().getFirst();
        return new GuideItemView(itemId, itemId, ingredient.count());
    }

    private void renderItem(
            GuiGraphicsExtractor graphics,
            GuideItemView item,
            int x,
            int y,
            int mouseX,
            int mouseY) {
        ItemStack stack = itemStack(item.itemId(), item.count());
        if (stack.isEmpty()) {
            graphics.text(font, "?", x + 5, y + 4, MUTED, false);
            return;
        }
        graphics.item(stack, x, y);
        graphics.itemDecorations(font, stack, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
    }

    private static boolean visibleDetail(int y, int height, GuideUiLayout.Rect detail) {
        return y + height >= detail.y() + 21 && y <= detail.y() + detail.height();
    }

    private void navigate(RecipeNavigationResult result) {
        notice = result.opened()
                ? Component.translatable("screen.openallay.recipe.viewer_opened").getString()
                : navigationFailure(result);
    }

    private static String navigationFailure(RecipeNavigationResult result) {
        String key = switch (result.code()) {
            case "exact_unsupported" -> "screen.openallay.recipe.exact_unsupported";
            case "preferred_viewer_unavailable" ->
                    "screen.openallay.recipe.preferred_viewer_unavailable";
            case "viewer_unavailable" -> "screen.openallay.recipe.viewer_unavailable";
            case "unknown_item" -> "screen.openallay.recipe.unknown_item";
            case "wrong_thread" -> "screen.openallay.recipe.wrong_thread";
            case "viewer_failure" -> "screen.openallay.recipe.viewer_failure";
            default -> null;
        };
        return key == null
                ? result.code() + ": " + result.message()
                : Component.translatable(key).getString();
    }

    private int evidence(GuiGraphicsExtractor graphics, GuideSource source, GuideUiLayout.Rect detail, int y) {
        var value = source.evidence();
        y = detailLine(graphics, "来源: " + value.sourceId(), detail, y);
        y = detailLine(graphics, "权威: " + value.authority(), detail, y);
        y = detailLine(graphics, "完整性: " + value.completeness(), detail, y);
        y = detailLine(graphics, "采集: " + value.capturedAt(), detail, y);
        y = detailLine(graphics, "Provenance: " + value.provenance(), detail, y);
        return y;
    }

    private int detailLine(GuiGraphicsExtractor graphics, String text, GuideUiLayout.Rect detail, int y) {
        return detailLine(graphics, Component.literal(text), detail, y);
    }

    private int detailLine(
            GuiGraphicsExtractor graphics, Component text, GuideUiLayout.Rect detail, int y) {
        for (FormattedCharSequence line : font.split(text, detail.width() - 16)) {
            if (y >= detail.y() + 21 && y < detail.y() + detail.height() - 10) {
                graphics.text(font, line, detail.x() + 8, y, TEXT, false);
            }
            y += 10;
        }
        return y + 2;
    }

    private void open(GuideUiRow.Tool tool) {
        selectedTool = tool;
        selectedSource = null;
        selectedSourceFocusId = null;
        detailScroll = 0;
        rebuildForDetail();
    }

    private void open(GuideSource source) {
        open(source, "source-detail:" + source.evidence().sourceId());
    }

    private void open(GuideSource source, String focusId) {
        selectedSource = source;
        selectedSourceFocusId = Objects.requireNonNull(focusId, "focusId");
        selectedTool = null;
        detailScroll = 0;
        rebuildForDetail();
    }

    private void rebuildForDetail() {
        GuideViewportAnchor anchor = layout == null ? null : virtualizer.anchorAt(scroll);
        boolean shouldFollow = followBottom;
        draft = composer == null ? draft : composer.getValue();
        rebuildWidgets();
        restoreTranscript(anchor, shouldFollow);
    }

    private boolean detailOpen() {
        return selectedTool != null || selectedSource != null;
    }

    private boolean refreshDetail(GuideUiView next) {
        boolean wasOpen = detailOpen();
        if (selectedTool != null) {
            GuideUiRow.Tool replacement = next.rows().stream()
                    .filter(GuideUiRow.Tool.class::isInstance)
                    .map(GuideUiRow.Tool.class::cast)
                            .filter(value -> toolFocusId(value).equals(toolFocusId(selectedTool)))
                    .findFirst().orElse(null);
            selectedTool = replacement;
        }
        if (selectedSource != null) {
            boolean retained = next.rows().stream().anyMatch(row -> switch (row) {
                case GuideUiRow.Assistant assistant -> assistant.sources().contains(selectedSource);
                case GuideUiRow.Tool tool -> tool.activity().sources().contains(selectedSource);
                default -> false;
            });
            if (!retained) {
                selectedSource = null;
                selectedSourceFocusId = null;
            }
        }
        if (!wasOpen) return false;
        if (!detailOpen()) return true;
        if (!next.selectedSession().equals(view.selectedSession())) {
            selectedTool = null;
            selectedSource = null;
            selectedSourceFocusId = null;
            detailScroll = 0;
            return true;
        }
        return false;
    }

    private void applyPendingProjection() {
        GuideSnapshot pending = pendingSnapshots.drain();
        GuideDisplayConfig nextDisplay = currentDisplay();
        if (pending == null && nextDisplay.equals(projectedDisplay)) return;
        GuideSnapshot snapshot = pending == null ? service.snapshot() : pending;
        applyProjection(GuideUiView.from(snapshot, nextDisplay), nextDisplay);
    }

    private void applyProjection(GuideUiView next, GuideDisplayConfig nextDisplay) {
        boolean changedSession = !view.selectedSession().equals(next.selectedSession());
        GuideViewportAnchor anchor = layout == null ? null : virtualizer.anchorAt(scroll);
        boolean shouldFollow = followBottom;
        boolean closedDetail = refreshDetail(next);
        projectedDisplay = nextDisplay;
        view = next;
        if (view.modelChoices().isEmpty()) {
            modelSelectorOpen = false;
            modelSelectorCursor = 0;
            modelSelectorScroll = 0;
        } else {
            modelSelectorCursor = Mth.clamp(
                    modelSelectorCursor, 0, view.modelChoices().size() - 1);
        }
        if (changedSession) {
            scroll = 0;
            followBottom = true;
            semanticLayouts.clear();
            semanticHashes.clear();
            stableRowHeights.clear();
            nativeViews.clear();
        }
        if ((changedSession || closedDetail) && composer != null) {
            draft = composer.getValue();
            rebuildWidgets();
        } else if (layout != null) {
            updateVirtualRows(Math.max(40, layout.transcript().width() - 18));
        }
        restoreTranscript(changedSession ? null : anchor, changedSession || shouldFollow);
        updateControls();
    }

    private void restoreTranscript(GuideViewportAnchor anchor, boolean shouldFollow) {
        if (layout == null) return;
        int viewportHeight = transcriptViewportHeight();
        scroll = shouldFollow
                ? virtualizer.maximumScroll(viewportHeight)
                : virtualizer.restore(anchor, scroll, viewportHeight);
    }

    private int transcriptViewportHeight() {
        return layout == null ? 0 : Math.max(0, layout.transcript().height() - 14);
    }

    private GuideDisplayConfig currentDisplay() {
        return Objects.requireNonNull(display.get(), "display config");
    }

    static GuideUiView project(
            GuideSnapshot snapshot, Supplier<GuideDisplayConfig> display) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(display, "display");
        return GuideUiView.from(
                snapshot, Objects.requireNonNull(display.get(), "display config"));
    }

    private void submit() {
        String question = composer.getValue().trim();
        if (question.isEmpty() || !view.canSend() || minecraft.player == null) return;
        draft = "";
        composer.setValue("");
        accept(service.ask(question), ignored -> notice = "");
    }

    private void cancel() {
        accept(service.cancel(), ignored -> notice = "");
    }

    private void retry() {
        GuideRequestSnapshot request = selectedRequests().stream()
                .filter(value -> value.status() == GuideRequestStatus.FAILED
                        || value.status() == GuideRequestStatus.CANCELLED
                        || value.status() == GuideRequestStatus.INTERRUPTED)
                .reduce((first, second) -> second).orElse(null);
        if (request != null) accept(service.retry(request.requestId()), ignored -> notice = "");
    }

    private void createSession() {
        int index = 1;
        String id = "session-1";
        while (containsSession(id)) id = "session-" + ++index;
        accept(service.selectSession(id), ignored -> {
            notice = "";
            scroll = 0;
        });
    }

    private boolean containsSession(String id) {
        for (GuideUiSession session : view.sessions()) {
            if (session.id().equals(id)) return true;
        }
        return false;
    }

    private void closeSession() {
        String sessionId = view.selectedSession();
        minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        confirmSessionDeletionAgain(sessionId);
                    } else {
                        minecraft.setScreenAndShow(this);
                    }
                },
                Component.translatable("screen.openallay.session.delete.first.title"),
                deleteConfirmationMessage(sessionId, false),
                Component.translatable("screen.openallay.session.delete.continue"),
                Component.translatable("screen.openallay.session.delete.cancel")));
    }

    private void confirmSessionDeletionAgain(String sessionId) {
        minecraft.setScreenAndShow(new ConfirmScreen(
                confirmed -> {
                    minecraft.setScreenAndShow(this);
                    if (confirmed) {
                        accept(service.closeSession(sessionId), deleted -> {
                            notice = Component.translatable(
                                    deleted
                                            ? "screen.openallay.session.delete.success"
                                            : "screen.openallay.session.delete.missing",
                                    sessionId).getString();
                            if (deleted) scroll = 0;
                        });
                    }
                },
                Component.translatable("screen.openallay.session.delete.second.title"),
                deleteConfirmationMessage(sessionId, true),
                Component.translatable("screen.openallay.session.delete.confirm"),
                Component.translatable("screen.openallay.session.delete.cancel")));
    }

    private void exportSession() {
        if (exportRunning) return;
        java.nio.file.Path gameDirectory = minecraft.gameDirectory.toPath();
        exportRunning = true;
        notice = Component.translatable("screen.openallay.session.export.running").getString();
        updateControls();
        service.captureSelectedSessionForExport().whenComplete((captured, captureFailure) -> {
            if (captureFailure != null || captured == null) {
                completeExportFailure();
                return;
            }
            if (captured instanceof ToolResult.Failure<?> failure) {
                completeExportFailure();
                return;
            }
            var snapshot = ((ToolResult.Success<
                    dev.openallay.guide.export.GuideSessionExportSnapshot>) captured).value();
            CompletableFuture.supplyAsync(
                            () -> new GuideSessionExporter(gameDirectory).export(snapshot),
                            EXPORT_EXECUTOR)
                    .whenComplete((exported, failure) -> minecraft.execute(() -> {
                        exportRunning = false;
                        notice = failure == null
                                ? Component.translatable(
                                        "screen.openallay.session.export.success",
                                        exported.filename(),
                                        exported.requestCount()).getString()
                                : Component.translatable(
                                        "screen.openallay.session.export.failed").getString();
                        updateControls();
                    }));
        });
    }

    private void completeExportFailure() {
        minecraft.execute(() -> {
            exportRunning = false;
            notice = Component.translatable(
                    "screen.openallay.session.export.failed").getString();
            updateControls();
        });
    }

    private void renderCopyAction(
            GuiGraphicsExtractor graphics,
            GuideUiRow row,
            String text,
            int x,
            int y,
            int width) {
        if (text == null || text.isBlank()) return;
        Component label = Component.translatable("screen.openallay.action.copy");
        int actionWidth = font.width(label) + 8;
        int actionX = x + width - actionWidth;
        String focusId = "copy:" + rowId(row);
        if (isFocused(focusedContentId, focusId)) {
            graphics.fill(actionX - 2, y - 2, actionX + actionWidth, y + 10, 0xFF31453F);
        }
        graphics.text(font, label, actionX + 2, y, MUTED, false);
        hits.add(new Hit(
                new GuideUiLayout.Rect(actionX - 2, y - 2, actionWidth + 2, 12),
                HitKind.CONTENT,
                () -> copyChatText(text),
                focusId,
                label.getString()));
    }

    private void copyChatText(String text) {
        try {
            minecraft.keyboardHandler.setClipboard(text);
            notice = Component.translatable("screen.openallay.copy.success").getString();
        } catch (RuntimeException failure) {
            notice = Component.translatable("screen.openallay.copy.failed").getString();
        }
    }

    static String copyableText(GuideUiRow row) {
        return switch (row) {
            case GuideUiRow.User value -> value.text();
            case GuideUiRow.Assistant value -> value.text();
            default -> null;
        };
    }

    static Component deleteConfirmationMessage(String sessionId, boolean finalConfirmation) {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session deletion target");
        }
        return Component.translatable(
                finalConfirmation
                        ? "screen.openallay.session.delete.second.message"
                        : "screen.openallay.session.delete.first.message",
                sessionId);
    }

    private List<GuideRequestSnapshot> selectedRequests() {
        GuideSnapshot snapshot = service.snapshot();
        return snapshot.sessions().stream()
                .filter(value -> value.sessionId().equals(snapshot.selectedSession()))
                .findFirst().orElseThrow().requests();
    }

    private <T> void accept(
            java.util.concurrent.CompletableFuture<ToolResult<T>> future, Consumer<T> success) {
        future.thenAccept(result -> {
            if (result instanceof ToolResult.Success<T> value) success.accept(value.value());
            else {
                ToolResult.Failure<T> failure = (ToolResult.Failure<T>) result;
                notice = failure.code() + ": " + failure.message();
            }
            updateControls();
        });
    }

    private void updateControls() {
        if (send == null) return;
        boolean inWorld = minecraft.player != null;
        send.active = inWorld && view.canSend() && !draft.trim().isEmpty();
        stop.active = view.canCancel();
        retry.active = view.canRetry();
        export.active = !exportRunning;
        model.setMessage(modelLabel());
        model.active = !view.modelChoices().isEmpty();
    }

    private Component modelLabel() {
        GuideUiModelChoice selected = view.selectedModel();
        Component label = choiceLabel(selected);
        Component value = selected.available()
                ? label
                : Component.translatable("screen.openallay.model.unavailable_short", label);
        return Component.literal("▾ ").append(value);
    }

    private void revealSelectedModel() {
        int selected = Math.max(0, view.modelChoices().indexOf(view.selectedModel()));
        modelSelectorCursor = selected;
        int visible = visibleModelChoiceCount();
        modelSelectorScroll = Mth.clamp(
                selected - Math.max(0, visible - 1),
                0,
                Math.max(0, view.modelChoices().size() - visible));
    }

    private void moveModelSelectorCursor(int delta) {
        int last = view.modelChoices().size() - 1;
        if (last < 0) return;
        modelSelectorCursor = Mth.clamp(modelSelectorCursor + delta, 0, last);
        int visible = visibleModelChoiceCount();
        if (modelSelectorCursor < modelSelectorScroll) {
            modelSelectorScroll = modelSelectorCursor;
        } else if (modelSelectorCursor >= modelSelectorScroll + visible) {
            modelSelectorScroll = modelSelectorCursor - visible + 1;
        }
        GuideUiModelChoice choice = view.modelChoices().get(modelSelectorCursor);
        focusedContentId = modelFocusId(choice);
        if (minecraft != null && minecraft.getNarrator().isActive()) {
            minecraft.getNarrator().saySystemNow(choiceLabel(choice));
        }
    }

    private int visibleModelChoiceCount() {
        if (modelSelectorButton == null) return 1;
        return Math.max(1, Math.min(8,
                (height - modelSelectorButton.y() - modelSelectorButton.height() - 12) / 20));
    }

    private GuideUiLayout.Rect modelSelectorBounds() {
        if (!modelSelectorOpen || modelSelectorButton == null) return null;
        int visible = Math.min(visibleModelChoiceCount(), view.modelChoices().size());
        return new GuideUiLayout.Rect(
                modelSelectorButton.x(),
                modelSelectorButton.y() + modelSelectorButton.height() + 2,
                modelSelectorButton.width(),
                Math.max(1, visible * 20));
    }

    private void renderModelSelector(GuiGraphicsExtractor graphics) {
        hits.removeIf(hit -> hit.kind() == HitKind.MODEL);
        GuideUiLayout.Rect menu = modelSelectorBounds();
        if (menu == null) return;
        int visible = Math.min(visibleModelChoiceCount(), view.modelChoices().size());
        int maximum = Math.max(0, view.modelChoices().size() - visible);
        modelSelectorScroll = Mth.clamp(modelSelectorScroll, 0, maximum);
        graphics.fill(menu.x(), menu.y(), menu.x() + menu.width(), menu.y() + menu.height(), 0xFF181B22);
        graphics.outline(menu.x(), menu.y(), menu.width(), menu.height(), ACCENT);
        graphics.enableScissor(menu.x() + 1, menu.y() + 1,
                menu.x() + menu.width() - 1, menu.y() + menu.height() - 1);
        for (int offset = 0; offset < visible; offset++) {
            int choiceIndex = modelSelectorScroll + offset;
            GuideUiModelChoice choice = view.modelChoices().get(choiceIndex);
            int y = menu.y() + offset * 20;
            if (choice.selected()) {
                graphics.fill(menu.x() + 1, y + 1, menu.x() + menu.width() - 1, y + 19, 0xFF355F59);
            }
            if (choiceIndex == modelSelectorCursor) {
                graphics.outline(menu.x() + 2, y + 2, menu.width() - 4, 16, 0xFFFFFFFF);
            }
            Component label = Component.literal(choice.selected() ? "✓ " : "  ")
                    .append(choiceLabel(choice));
            if (!choice.available()) {
                label = label.copy().append(Component.translatable(
                        "screen.openallay.model.choice_unavailable"));
            }
            graphics.text(font, label, menu.x() + 5, y + 6,
                    choice.available() ? TEXT : MUTED, false);
            String focusId = modelFocusId(choice);
            hits.add(new Hit(
                    new GuideUiLayout.Rect(menu.x() + 1, y + 1, menu.width() - 2, 18),
                    HitKind.MODEL,
                    () -> selectModel(choice),
                    focusId,
                    label.getString()));
        }
        graphics.disableScissor();
    }

    private void selectModel(GuideUiModelChoice choice) {
        modelSelectorOpen = false;
        if (!choice.available() || choice.selected()) return;
        accept(service.setModelSelection(choice.selection()), ignored -> notice = "");
    }

    private boolean renderNativeRecipe(
            GuideUiRow.Assistant assistant,
            GuiGraphicsExtractor graphics,
            net.minecraft.client.gui.Font font,
            dev.openallay.guide.semantic.RichComponent.RecipeGrid component,
            GuideUiLayout.Rect bounds,
            int mouseX,
            int mouseY,
            long ticks) {
        NativeDomainViewBinding.Recipe binding = NativeDomainViewBindings.recipe(
                view, assistant, component).orElse(null);
        return binding != null && nativeViews.render(
                binding,
                new NativeDomainView.RenderContext(
                        graphics, font, bounds, mouseX, mouseY, ticks));
    }

    static String toolFocusId(GuideUiRow.Tool tool) {
        return "tool:" + tool.requestId() + ":" + tool.activity().invocationId();
    }

    static String sourceFocusId(
            GuideUiRow.Assistant assistant, GuideSource source, int sourceIndex) {
        return "source:" + assistant.requestId() + ":" + assistant.ordinal() + ":"
                + sourceIndex + ":" + source.evidence().sourceId();
    }

    static String modelFocusId(GuideUiModelChoice choice) {
        String id = choice.selection().kind() == GuideModelSelection.Kind.SERVER
                ? "server" : choice.selection().profileId();
        return "model:" + choice.selection().kind() + ":" + id;
    }

    static boolean isFocused(String focusedId, String candidateId) {
        return focusedId != null && focusedId.equals(candidateId);
    }

    private Component modelStatus() {
        GuideUiModelChoice selected = view.selectedModel();
        Component selectedLabel = choiceLabel(selected);
        if (!selected.available()) {
            return Component.translatable(
                    "screen.openallay.model.selected_unavailable", selectedLabel);
        }
        if (view.modelSwitchPending()) {
            return Component.translatable(
                    "screen.openallay.model.running_next",
                    choiceLabel(view.runningModel()),
                    selectedLabel);
        }
        return Component.translatable("screen.openallay.model.using", selectedLabel);
    }

    private static Component choiceLabel(GuideUiModelChoice choice) {
        return choice.selection().kind() == GuideModelSelection.Kind.SERVER
                ? Component.translatable("screen.openallay.model.server")
                : Component.translatable(
                        "screen.openallay.model.client", choice.displayName());
    }

    static String sourceLabel(GuideSource source, boolean debugMode) {
        if (!debugMode) {
            return Component.translatable("screen.openallay.detail.source_link").getString();
        }
        return "source · " + source.evidence().sourceId() + " · "
                + source.evidence().authority() + "/" + source.evidence().completeness();
    }

    private static Component friendlyTool(String id) {
        int separator = id.indexOf(':');
        String name = separator >= 0 ? id.substring(separator + 1) : id;
        return switch (name) {
            case "search_recipes" -> Component.translatable("screen.openallay.tool.search_recipes");
            case "get_recipe" -> Component.translatable("screen.openallay.tool.get_recipe");
            case "find_item_usages" -> Component.translatable("screen.openallay.tool.find_item_usages");
            case "inspect_inventory" -> Component.translatable("screen.openallay.tool.inspect_inventory");
            case "calculate_craftability" -> Component.translatable(
                    "screen.openallay.tool.calculate_craftability");
            case "resolve_resource" -> Component.translatable("screen.openallay.tool.resolve_resource");
            case "search_knowledge" -> Component.translatable("screen.openallay.tool.search_knowledge");
            case "load_knowledge", "get_knowledge_document" ->
                    Component.translatable("screen.openallay.tool.load_knowledge");
            case "list_knowledge_sources" ->
                    Component.translatable("screen.openallay.tool.list_knowledge_sources");
            case "get_patchouli_multiblock" ->
                    Component.translatable("screen.openallay.tool.get_patchouli_multiblock");
            case "load_skill" -> Component.translatable("screen.openallay.tool.load_skill");
            case "platform_info" -> Component.translatable("screen.openallay.tool.platform_info");
            case "player_context" -> Component.translatable("screen.openallay.tool.player_context");
            case "inspect_game_state" -> Component.translatable(
                    "screen.openallay.tool.inspect_game_state");
            default -> Component.literal(name);
        };
    }

    enum ComposerKeyAction { SUBMIT, NEWLINE, DELEGATE }

    static ComposerKeyAction composerKeyAction(
            boolean composerFocused,
            boolean confirmation,
            boolean shiftDown,
            boolean controlDown) {
        if (!composerFocused || !confirmation) return ComposerKeyAction.DELEGATE;
        if (controlDown) return ComposerKeyAction.SUBMIT;
        return shiftDown ? ComposerKeyAction.NEWLINE : ComposerKeyAction.SUBMIT;
    }

    static final class TickCoalescer<T> {
        private final java.util.concurrent.atomic.AtomicReference<T> pending =
                new java.util.concurrent.atomic.AtomicReference<>();

        void offer(T value) {
            pending.set(Objects.requireNonNull(value, "value"));
        }

        T drain() {
            return pending.getAndSet(null);
        }
    }

    /** Development-only positioning used by the retained real-client screenshot probe. */
    public void positionForDevelopmentProbe(double fraction) {
        if (!Boolean.getBoolean("openallay.e2e.enabled")) {
            throw new IllegalStateException("development probe is disabled");
        }
        if (!Double.isFinite(fraction) || fraction < 0.0D || fraction > 1.0D) {
            throw new IllegalArgumentException("scroll fraction must be 0..1");
        }
        int maximum = virtualizer.maximumScroll(transcriptViewportHeight());
        scroll = (int) Math.round(maximum * fraction);
        followBottom = fraction == 1.0D;
    }

    /** Development-only Tool detail selection used by screenshot acceptance. */
    public void selectToolForDevelopmentProbe(int index) {
        if (!Boolean.getBoolean("openallay.e2e.enabled")) {
            throw new IllegalStateException("development probe is disabled");
        }
        List<GuideUiRow.Tool> tools = view.rows().stream()
                .filter(GuideUiRow.Tool.class::isInstance)
                .map(GuideUiRow.Tool.class::cast)
                .toList();
        if (index < 0 || index >= tools.size()) {
            throw new IllegalArgumentException("tool index is unavailable");
        }
        open(tools.get(index));
    }

    /** Development-only count used to keep failed-report screenshots non-crashing. */
    public int toolCountForDevelopmentProbe() {
        if (!Boolean.getBoolean("openallay.e2e.enabled")) {
            throw new IllegalStateException("development probe is disabled");
        }
        return (int) view.rows().stream().filter(GuideUiRow.Tool.class::isInstance).count();
    }

    /** Development-only explicit-selector state used by retained screenshot acceptance. */
    public void openModelSelectorForDevelopmentProbe() {
        if (!Boolean.getBoolean("openallay.e2e.enabled")) {
            throw new IllegalStateException("development probe is disabled");
        }
        modelSelectorOpen = true;
        revealSelectedModel();
    }

    /** Development-only selector cleanup used before the narrow-layout screenshot. */
    public void closeModelSelectorForDevelopmentProbe() {
        if (!Boolean.getBoolean("openallay.e2e.enabled")) {
            throw new IllegalStateException("development probe is disabled");
        }
        modelSelectorOpen = false;
    }

    /** Development-only readiness check so retained screenshots prove the active strip rendered. */
    public boolean hasRenderedActiveProgressForDevelopmentProbe() {
        return activeProgressRenderFrames >= 2;
    }

    static final class StableRowHeights {
        private final Map<String, Integer> heights = new LinkedHashMap<>();
        private int width = -1;

        void begin(int replacementWidth) {
            if (replacementWidth <= 0) throw new IllegalArgumentException("row width must be positive");
            if (width != replacementWidth) {
                width = replacementWidth;
                heights.clear();
            }
        }

        int retain(String rowId, int measuredHeight, boolean stabilize) {
            if (rowId == null || rowId.isBlank() || measuredHeight <= 0) {
                throw new IllegalArgumentException("stable row measurement is invalid");
            }
            if (!stabilize) {
                heights.put(rowId, measuredHeight);
                return measuredHeight;
            }
            return heights.merge(rowId, measuredHeight, Math::max);
        }

        void retainOnly(java.util.Set<String> rowIds) {
            heights.keySet().retainAll(rowIds);
        }

        void clear() {
            heights.clear();
        }
    }

    private enum HitKind { SESSION, CONTENT, DETAIL, MODEL }
    private record Hit(
            GuideUiLayout.Rect rect,
            HitKind kind,
            Runnable action,
            String focusId,
            String narration) {
        private Hit(GuideUiLayout.Rect rect, HitKind kind, Runnable action) {
            this(rect, kind, action, null, "");
        }
    }
}
