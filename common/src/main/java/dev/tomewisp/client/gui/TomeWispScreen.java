package dev.tomewisp.client.gui;

import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideService;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideSubscription;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideHistoryPageState;
import dev.tomewisp.guide.history.GuideHistoryPageRequest;
import dev.tomewisp.guide.ui.GuideUiLayout;
import dev.tomewisp.guide.ui.GuideUiModelChoice;
import dev.tomewisp.guide.ui.GuideUiProgress;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.guide.ui.GuideUiSession;
import dev.tomewisp.guide.ui.GuideUiView;
import dev.tomewisp.guide.ui.GuideDetailCard;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.ui.GuideDisplayRuntime;
import dev.tomewisp.guide.ui.GuideItemView;
import dev.tomewisp.guide.ui.GuideRecipeCard;
import dev.tomewisp.guide.ui.GuideToolDetailView;
import dev.tomewisp.guide.ui.GuideUiClickRoute;
import dev.tomewisp.guide.ui.GuideTranscriptVirtualizer;
import dev.tomewisp.guide.ui.GuideViewportAnchor;
import dev.tomewisp.guide.ui.SemanticLayout;
import dev.tomewisp.guide.ui.SemanticLayoutCache;
import dev.tomewisp.guide.ui.SemanticLayoutEngine;
import dev.tomewisp.recipe.RecipeNavigationResult;
import dev.tomewisp.recipe.config.RecipeClientRuntime;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
public final class TomeWispScreen extends Screen {
    private static final int PANEL = 0xD0181B22;
    private static final int PANEL_ALT = 0xD0242933;
    private static final int ACCENT = 0xFF72D5C4;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFFA9B3BE;
    private static final int ERROR = 0xFFFF7D7D;
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
    private String draft = "";
    private String notice = "";
    private int scroll;
    private int detailScroll;
    private int detailContentHeight;
    private int activeProgressRenderFrames;
    private boolean sessionOverlay;
    private GuideUiRow.Tool selectedTool;
    private GuideSource selectedSource;
    private final List<Hit> hits = new ArrayList<>();
    private final GuideTranscriptVirtualizer virtualizer = new GuideTranscriptVirtualizer();
    private final SemanticLayoutCache semanticLayouts = new SemanticLayoutCache();
    private final MinecraftSemanticRenderer semanticRenderer =
            new MinecraftSemanticRenderer(new MinecraftSemanticResolver());
    private final Map<String, Integer> semanticHashes = new LinkedHashMap<>();
    private boolean followBottom = true;
    private String focusedContentId;
    private GuideDisplayConfig projectedDisplay;
    private long presentationTicks;

    public TomeWispScreen(GuideService service) {
        this(service, RecipeClientRuntime.defaults(), GuideDisplayConfig.defaults());
    }

    public TomeWispScreen(GuideService service, RecipeClientRuntime recipeClient) {
        this(service, recipeClient, GuideDisplayConfig.defaults());
    }

    public TomeWispScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig) {
        this(service, recipeClient, displayConfig, null);
    }

    public TomeWispScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig,
            GuideFailure displayFailure) {
        this(service, recipeClient, displayConfig, displayFailure, null);
    }

    public TomeWispScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            GuideDisplayConfig displayConfig,
            GuideFailure displayFailure,
            Runnable settingsOpener) {
        this(service, recipeClient, () -> displayConfig, displayFailure, settingsOpener);
    }

    public TomeWispScreen(
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

    private TomeWispScreen(
            GuideService service,
            RecipeClientRuntime recipeClient,
            Supplier<GuideDisplayConfig> display,
            GuideFailure displayFailure,
            Runnable settingsOpener) {
        super(Component.translatable("screen.tomewisp.guide"));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.recipeClient = java.util.Objects.requireNonNull(recipeClient, "recipeClient");
        this.display = java.util.Objects.requireNonNull(display, "display");
        this.settingsOpener = settingsOpener;
        this.projectedDisplay = currentDisplay();
        this.view = GuideUiView.from(service.snapshot(), projectedDisplay);
        List<String> startupNotices = new ArrayList<>();
        recipeClient.failure().ifPresent(failure -> startupNotices.add(Component.translatable(
                "screen.tomewisp.recipe.invalid_config", failure.code()).getString()));
        if (displayFailure != null) {
            startupNotices.add(Component.translatable(
                    "screen.tomewisp.debug.invalid_config").getString());
        }
        notice = String.join(" · ", startupNotices);
    }

    @Override
    protected void init() {
        layout = GuideUiLayout.calculate(width, height, detailOpen());
        GuideUiLayout.Rect top = layout.topBar();
        int x = top.x() + top.width() - (settingsOpener == null ? 252 : 276);
        addRenderableWidget(Button.builder(Component.literal("会话"), button -> sessionOverlay = !sessionOverlay)
                .bounds(x, top.y() + 2, 32, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> createSession())
                .bounds(x + 36, top.y() + 2, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> closeSession())
                .bounds(x + 64, top.y() + 2, 32, 20).build());
        model = addRenderableWidget(Button.builder(modelLabel(), button -> cycleModel())
                .bounds(x + 100, top.y() + 2, 128, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> service.refreshCapabilities())
                .bounds(x + 232, top.y() + 2, 20, 20).build());
        if (settingsOpener != null) {
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.tomewisp.settings.short"),
                            button -> settingsOpener.run())
                    .bounds(x + 256, top.y() + 2, 20, 20)
                    .build());
        }

        GuideUiLayout.Rect area = layout.composer();
        int actionWidth = 54;
        composer = MultiLineEditBox.builder()
                .setX(area.x()).setY(area.y())
                .setPlaceholder(Component.translatable("screen.tomewisp.composer.placeholder"))
                .build(font, Math.max(80, area.width() - actionWidth - 6), area.height(),
                        Component.translatable("screen.tomewisp.composer.narration"));
        composer.setValue(draft, true);
        composer.setValueListener(value -> draft = value);
        addRenderableWidget(composer);
        int actionsX = area.x() + area.width() - actionWidth;
        send = addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.action.send"), button -> submit())
                .bounds(actionsX, area.y(), actionWidth, 20).build());
        stop = addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.action.stop"), button -> cancel())
                .bounds(actionsX, area.y() + 24, actionWidth, 20).build());
        retry = addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.action.retry"), button -> retry())
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
        subscription = service.subscribe(pendingSnapshots::offer);
    }

    @Override
    public void removed() {
        draft = composer == null ? draft : composer.getValue();
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
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
        applyPendingProjection();
        if (layout != null) requestViewportHistory(layout.transcript());
        updateControls();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
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
    }

    private void renderTop(GuiGraphicsExtractor graphics) {
        GuideUiLayout.Rect top = layout.topBar();
        graphics.fill(top.x(), top.y(), top.x() + top.width(), top.y() + top.height(), PANEL);
        graphics.text(font, Component.literal("TomeWisp 书灵").withStyle(ChatFormatting.BOLD),
                top.x() + 8, top.y() + 6, TEXT);
        graphics.text(font, modelStatus(), top.x() + 8, top.y() + 25, MUTED, false);
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
        graphics.text(font, "会话", rail.x() + 8, rail.y() + 8, ACCENT);
        int y = rail.y() + 24;
        for (GuideUiSession session : view.sessions()) {
            int color = session.selected() ? 0xFF355F59 : 0xFF20252E;
            graphics.fill(rail.x() + 4, y, rail.x() + rail.width() - 4, y + 20, color);
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
                    progress.phase() == dev.tomewisp.guide.GuideRequestPhase.ENDPOINT_WAIT
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
                "screen.tomewisp.progress.elapsed",
                formatDuration(Duration.between(progress.requestStartedAt(), now))));
        if (progress.retryAt() != null) {
            message.append(" · ").append(Component.translatable(
                    "screen.tomewisp.progress.retry_in",
                    formatDuration(Duration.between(now, progress.retryAt()))));
            if (progress.attempt() > 0) {
                message.append(" · ").append(Component.translatable(
                        "screen.tomewisp.progress.attempt", progress.attempt()));
            }
        } else if (progress.deadlineAt() != null) {
            message.append(" · ").append(Component.translatable(
                    "screen.tomewisp.progress.remaining",
                    formatDuration(Duration.between(now, progress.deadlineAt()))));
        } else if (progress.phase()
                == dev.tomewisp.guide.GuideRequestPhase.RESPONSE_STREAMING) {
            message.append(" · ").append(Component.translatable(
                    "screen.tomewisp.progress.last_update",
                    formatDuration(Duration.between(progress.lastProgressAt(), now))));
        }
        if (debugMode && progress.retryAt() == null && progress.attempt() > 0) {
            message.append(" · ").append(Component.translatable(
                    "screen.tomewisp.progress.attempt", progress.attempt()));
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
            graphics.text(font, "还没有对话。输入问题，TomeWisp 会先查证再回答。",
                    area.x() + 9, contentTop, MUTED, false);
        }
        for (int index = window.fromIndex(); index < window.toIndexExclusive(); index++) {
            int y = contentTop - scroll + virtualizer.offset(index);
            renderRow(graphics, view.rows().get(index), area.x() + 9, y, textWidth, mouseX, mouseY);
        }
        hits.removeIf(hit -> hit.kind() == HitKind.CONTENT && !intersects(hit.rect(), area));
        hits.stream()
                .filter(hit -> hit.kind() == HitKind.CONTENT
                        && java.util.Objects.equals(hit.focusId(), focusedContentId))
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
            graphics.text(font, "你", x, y, ACCENT, false);
            y += 11;
            y = renderWrapped(graphics, GuideMarkup.paragraphs(user.text()), x + 6, y, width - 6, TEXT);
            return y + 8;
        }
        if (row instanceof GuideUiRow.Assistant assistant) {
            graphics.text(font, assistant.streaming() ? "书灵 · 思索中" : "书灵", x, y, ACCENT, false);
            y += 11;
            if (assistant.text().isBlank()) {
                graphics.text(font, "正在准备证据…", x + 6, y, MUTED, false);
                y += 10;
            } else {
                SemanticLayout semantic = semanticLayout(assistant, width - 6);
                MinecraftSemanticRenderer.Result rendered = semanticRenderer.render(
                        graphics, font, semantic, x + 6, y, width - 6,
                        mouseX, mouseY, projectedDisplay.animationsEnabled(),
                        presentationTicks);
                for (MinecraftSemanticRenderer.Hit hit : rendered.hits()) {
                    String focusId = "semantic:" + hit.intent();
                    hits.add(new Hit(
                            hit.bounds(), HitKind.CONTENT,
                            () -> semanticIntent(hit.intent()), focusId,
                            semanticIntentNarration(hit.intent())));
                }
                y = rendered.bottom();
            }
            for (GuideSource source : assistant.sources()) {
                int sourceY = y;
                String label = sourceLabel(source, projectedDisplay.debugMode());
                graphics.text(font, label, x + 6, sourceY, ACCENT, false);
                hits.add(new Hit(new GuideUiLayout.Rect(x + 4, sourceY - 2, width - 8, 12),
                        HitKind.CONTENT, () -> open(source)));
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
            graphics.fill(x + 2, y - 2, x + width - 2, y + rowHeight - 6, PANEL_ALT);
            graphics.text(font, Component.literal(icon + " ").append(friendlyTool(activity.toolId())),
                    x + 7, y + 2, color, false);
            int summaryY = y + 14;
            for (FormattedCharSequence summary : summaries) {
                graphics.text(font, summary, x + 9, summaryY, MUTED, false);
                summaryY += 10;
            }
            hits.add(new Hit(new GuideUiLayout.Rect(
                            x + 2, y - 2, width - 4, rowHeight - 4),
                    HitKind.CONTENT, () -> open(tool)));
            return y + rowHeight;
        }
        if (row instanceof GuideUiRow.Persistence persistence) {
            int color = persistence.state()
                            == dev.tomewisp.guide.GuidePersistenceSnapshot.State.UNAVAILABLE
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
                ? Component.translatable("screen.tomewisp.history.interrupted")
                : Component.literal(status.text());
        graphics.text(font, message, x + 6, y, color, false);
        return y + 18;
    }

    private void updateVirtualRows(int width) {
        ArrayList<GuideTranscriptVirtualizer.Row> measured = new ArrayList<>();
        Map<String, Integer> nextHashes = new LinkedHashMap<>();
        for (GuideUiRow row : view.rows()) {
            String id = rowId(row);
            if (row instanceof GuideUiRow.Assistant assistant) {
                int hash = assistant.semantic().hashCode();
                nextHashes.put(id, hash);
                if (!java.util.Objects.equals(semanticHashes.get(id), hash)) {
                    semanticLayouts.invalidateRow(id);
                }
            }
            measured.add(new GuideTranscriptVirtualizer.Row(id, measureRow(row, width)));
        }
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
        if (session == null || session.historyWindow().state() != GuideHistoryPageState.IDLE
                || session.historyWindow().totalRequests() == 0) return;
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

    private void renderDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!detailOpen()) return;
        hits.removeIf(hit -> hit.kind() == HitKind.DETAIL);
        GuideUiLayout.Rect detail = layout.detail();
        graphics.fill(detail.x(), detail.y(), detail.x() + detail.width(), detail.y() + detail.height(), 0xF02A303A);
        graphics.outline(detail.x(), detail.y(), detail.width(), detail.height(), ACCENT);
        graphics.text(font, Component.translatable("screen.tomewisp.detail.title"),
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
                        Component.translatable("screen.tomewisp.debug.section").getString(), detail, y + 4);
                y = detailLine(graphics, "invocationId: " + debug.invocationId(), detail, y);
                y = detailLine(graphics, "toolId: " + debug.toolId(), detail, y);
                if (!debug.validationDiagnostic().isBlank()) {
                    y = detailLine(graphics,
                            "validation: " + debug.validationDiagnostic(), detail, y);
                }
                for (GuideSource source : debug.sources()) {
                    y = evidence(graphics, source, detail, y);
                }
                if (debug.normalized() != null) {
                    y = detailLine(graphics, "normalized: " + debug.normalized(), detail, y);
                }
            }
        } else if (selectedSource != null) {
            if (projectedDisplay.debugMode()) {
                y = evidence(graphics, selectedSource, detail, y);
            } else {
                y = detailLine(graphics,
                        Component.translatable("screen.tomewisp.detail.source_friendly").getString(),
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
            case GuideDetailCard.Text text -> textCard(graphics, text, detail, y);
            case GuideDetailCard.Error error -> errorCard(graphics, error, detail, y);
        };
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
                    ? Component.translatable("screen.tomewisp.craftability.ready").getString()
                    : Component.translatable("screen.tomewisp.craftability.missing").getString();
            graphics.text(font, state, left + 7, y + 6, card.craftable() ? 0xFF7FC8A9 : 0xFFFFD479, false);
            graphics.text(font,
                    Component.translatable(
                            "screen.tomewisp.craftability.maximum", card.maximumCrafts()),
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
                                    "screen.tomewisp.craftability.need", requirement.missing()),
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
                        Component.translatable("screen.tomewisp.recipe.workstation", card.workstation()),
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
                    Component.translatable("screen.tomewisp.recipe.recipes"),
                    actionX,
                    actionY,
                    canBrowse,
                    () -> navigate(recipeClient.openRecipes(output.itemId())));
            actionX = recipeAction(
                    graphics,
                    Component.translatable("screen.tomewisp.recipe.usages"),
                    actionX + 7,
                    actionY,
                    canBrowse,
                    () -> navigate(recipeClient.openUsages(output.itemId())));
            var exact = card.references().stream().filter(recipeClient::supportsExact).findFirst();
            recipeAction(
                    graphics,
                    Component.translatable(exact.isPresent()
                            ? "screen.tomewisp.recipe.open_exact"
                            : "screen.tomewisp.recipe.open_exact_unavailable"),
                    actionX + 7,
                    actionY,
                    exact.isPresent(),
                    () -> navigate(recipeClient.openExact(exact.orElseThrow())));
            if (!canBrowse) {
                graphics.text(
                        font,
                        Component.translatable("screen.tomewisp.recipe.viewer_unavailable"),
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
                ? Component.translatable("screen.tomewisp.recipe.viewer_opened").getString()
                : navigationFailure(result);
    }

    private static String navigationFailure(RecipeNavigationResult result) {
        String key = switch (result.code()) {
            case "exact_unsupported" -> "screen.tomewisp.recipe.exact_unsupported";
            case "preferred_viewer_unavailable" ->
                    "screen.tomewisp.recipe.preferred_viewer_unavailable";
            case "viewer_unavailable" -> "screen.tomewisp.recipe.viewer_unavailable";
            case "unknown_item" -> "screen.tomewisp.recipe.unknown_item";
            case "wrong_thread" -> "screen.tomewisp.recipe.wrong_thread";
            case "viewer_failure" -> "screen.tomewisp.recipe.viewer_failure";
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
        detailScroll = 0;
        rebuildForDetail();
    }

    private void open(GuideSource source) {
        selectedSource = source;
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
                    .filter(value -> value.activity().invocationId()
                            .equals(selectedTool.activity().invocationId()))
                    .findFirst().orElse(null);
            selectedTool = replacement;
        }
        if (selectedSource != null) {
            boolean retained = next.rows().stream().anyMatch(row -> switch (row) {
                case GuideUiRow.Assistant assistant -> assistant.sources().contains(selectedSource);
                case GuideUiRow.Tool tool -> tool.activity().sources().contains(selectedSource);
                default -> false;
            });
            if (!retained) selectedSource = null;
        }
        if (!wasOpen) return false;
        if (!detailOpen()) return true;
        if (!next.selectedSession().equals(view.selectedSession())) {
            selectedTool = null;
            selectedSource = null;
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
        if (changedSession) {
            scroll = 0;
            followBottom = true;
            semanticLayouts.clear();
            semanticHashes.clear();
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

    private void cycleModel() {
        List<GuideUiModelChoice> choices = view.modelChoices();
        int selected = choices.indexOf(view.selectedModel());
        for (int offset = 1; offset <= choices.size(); offset++) {
            GuideUiModelChoice candidate = choices.get((selected + offset) % choices.size());
            if (candidate.available() && !candidate.selected()) {
                accept(service.setModelSelection(candidate.selection()), ignored -> notice = "");
                return;
            }
        }
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
        accept(service.closeSession(view.selectedSession()), ignored -> {
            notice = "";
            scroll = 0;
        });
    }

    private List<GuideRequestSnapshot> selectedRequests() {
        return service.snapshot().sessions().stream()
                .filter(value -> value.sessionId().equals(service.snapshot().selectedSession()))
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
        model.setMessage(modelLabel());
        model.active = view.modelChoices().stream()
                .anyMatch(choice -> choice.available() && !choice.selected());
    }

    private Component modelLabel() {
        GuideUiModelChoice selected = view.selectedModel();
        Component label = choiceLabel(selected);
        return selected.available()
                ? label
                : Component.translatable("screen.tomewisp.model.unavailable_short", label);
    }

    private Component modelStatus() {
        GuideUiModelChoice selected = view.selectedModel();
        Component selectedLabel = choiceLabel(selected);
        if (!selected.available()) {
            return Component.translatable(
                    "screen.tomewisp.model.selected_unavailable", selectedLabel);
        }
        if (view.modelSwitchPending()) {
            return Component.translatable(
                    "screen.tomewisp.model.running_next",
                    choiceLabel(view.runningModel()),
                    selectedLabel);
        }
        return Component.translatable("screen.tomewisp.model.using", selectedLabel);
    }

    private static Component choiceLabel(GuideUiModelChoice choice) {
        return choice.selection().kind() == GuideModelSelection.Kind.SERVER
                ? Component.translatable("screen.tomewisp.model.server")
                : Component.translatable(
                        "screen.tomewisp.model.client", choice.displayName());
    }

    static String sourceLabel(GuideSource source, boolean debugMode) {
        if (!debugMode) {
            return Component.translatable("screen.tomewisp.detail.source_link").getString();
        }
        return "source · " + source.evidence().sourceId() + " · "
                + source.evidence().authority() + "/" + source.evidence().completeness();
    }

    private static Component friendlyTool(String id) {
        int separator = id.indexOf(':');
        String name = separator >= 0 ? id.substring(separator + 1) : id;
        return switch (name) {
            case "search_recipes" -> Component.translatable("screen.tomewisp.tool.search_recipes");
            case "get_recipe" -> Component.translatable("screen.tomewisp.tool.get_recipe");
            case "find_item_usages" -> Component.translatable("screen.tomewisp.tool.find_item_usages");
            case "inspect_inventory" -> Component.translatable("screen.tomewisp.tool.inspect_inventory");
            case "calculate_craftability" -> Component.translatable(
                    "screen.tomewisp.tool.calculate_craftability");
            case "resolve_resource" -> Component.translatable("screen.tomewisp.tool.resolve_resource");
            case "search_knowledge" -> Component.translatable("screen.tomewisp.tool.search_knowledge");
            case "load_knowledge", "get_knowledge_document" ->
                    Component.translatable("screen.tomewisp.tool.load_knowledge");
            case "list_knowledge_sources" ->
                    Component.translatable("screen.tomewisp.tool.list_knowledge_sources");
            case "get_patchouli_multiblock" ->
                    Component.translatable("screen.tomewisp.tool.get_patchouli_multiblock");
            case "load_skill" -> Component.translatable("screen.tomewisp.tool.load_skill");
            case "platform_info" -> Component.translatable("screen.tomewisp.tool.platform_info");
            case "player_context" -> Component.translatable("screen.tomewisp.tool.player_context");
            case "inspect_game_state" -> Component.translatable(
                    "screen.tomewisp.tool.inspect_game_state");
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
        if (!Boolean.getBoolean("tomewisp.e2e.enabled")) {
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
        if (!Boolean.getBoolean("tomewisp.e2e.enabled")) {
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

    /** Development-only readiness check so retained screenshots prove the active strip rendered. */
    public boolean hasRenderedActiveProgressForDevelopmentProbe() {
        return activeProgressRenderFrames >= 2;
    }

    private enum HitKind { SESSION, CONTENT, DETAIL }
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
