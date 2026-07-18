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
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.ui.GuideUiLayout;
import dev.tomewisp.guide.ui.GuideUiModelChoice;
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
import dev.tomewisp.recipe.RecipeNavigationResult;
import dev.tomewisp.recipe.config.RecipeClientRuntime;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

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
    private int contentHeight;
    private int detailScroll;
    private int detailContentHeight;
    private boolean sessionOverlay;
    private GuideUiRow.Tool selectedTool;
    private GuideSource selectedSource;
    private final List<Hit> hits = new ArrayList<>();
    private GuideDisplayConfig projectedDisplay;

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
        int x = top.x() + top.width() - (settingsOpener == null ? 176 : 200);
        addRenderableWidget(Button.builder(Component.literal("会话"), button -> sessionOverlay = !sessionOverlay)
                .bounds(x, top.y() + 2, 32, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> createSession())
                .bounds(x + 36, top.y() + 2, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> closeSession())
                .bounds(x + 64, top.y() + 2, 32, 20).build());
        model = addRenderableWidget(Button.builder(modelLabel(), button -> cycleModel())
                .bounds(x + 100, top.y() + 2, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> service.refreshCapabilities())
                .bounds(x + 156, top.y() + 2, 20, 20).build());
        if (settingsOpener != null) {
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.tomewisp.settings.short"),
                            button -> settingsOpener.run())
                    .bounds(x + 180, top.y() + 2, 20, 20)
                    .build());
        }

        GuideUiLayout.Rect area = layout.composer();
        int actionWidth = 54;
        composer = MultiLineEditBox.builder()
                .setX(area.x()).setY(area.y())
                .setPlaceholder(Component.literal("询问整合包、配方或机器；Ctrl+Enter 发送"))
                .build(font, Math.max(80, area.width() - actionWidth - 6), area.height(),
                        Component.literal("TomeWisp 提问输入框"));
        composer.setValue(draft, true);
        composer.setValueListener(value -> draft = value);
        addRenderableWidget(composer);
        int actionsX = area.x() + area.width() - actionWidth;
        send = addRenderableWidget(Button.builder(Component.literal("发送"), button -> submit())
                .bounds(actionsX, area.y(), actionWidth, 20).build());
        stop = addRenderableWidget(Button.builder(Component.literal("停止"), button -> cancel())
                .bounds(actionsX, area.y() + 24, actionWidth, 20).build());
        retry = addRenderableWidget(Button.builder(Component.literal("重试"), button -> retry())
                .bounds(actionsX, area.y() + 48, actionWidth, 20).build());
        updateControls();
        setInitialFocus(composer);
    }

    @Override
    public void added() {
        subscription = service.subscribe(snapshot -> {
            GuideDisplayConfig displayConfig = currentDisplay();
            GuideUiView next = GuideUiView.from(snapshot, displayConfig);
            boolean changedSession = !view.selectedSession().equals(next.selectedSession());
            boolean closedDetail = refreshDetail(next);
            projectedDisplay = displayConfig;
            view = next;
            if (changedSession) scroll = 0;
            if ((changedSession || closedDetail) && composer != null) rebuildForDetail();
            updateControls();
        });
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
        draft = composer == null ? draft : composer.getValue();
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        updateControls();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && event.hasControlDownWithQuirk()) {
            submit();
            return true;
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
            int maximum = Math.max(0, contentHeight - layout.transcript().height() + 12);
            scroll = Mth.clamp(scroll - (int) Math.round(scrollY * 24), 0, maximum);
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
                    detailHits.get(route.actionIndex()).action().run();
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
                        hit.action().run();
                        return true;
                    }
                }
            }
            for (Hit hit : List.copyOf(hits)) {
                if (hit.rect().contains(event.x(), event.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        refreshDisplayProjection();
        graphics.fill(0, 0, width, height, 0xC00B0D12);
        renderTop(graphics);
        renderSessions(graphics);
        renderTranscript(graphics);
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

    private void renderTranscript(GuiGraphicsExtractor graphics) {
        hits.removeIf(hit -> hit.kind() == HitKind.CONTENT);
        GuideUiLayout.Rect area = layout.transcript();
        graphics.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), PANEL);
        graphics.enableScissor(area.x(), area.y(), area.x() + area.width(), area.y() + area.height());
        int y = area.y() + 7 - scroll;
        int textWidth = Math.max(40, area.width() - 18);
        if (view.rows().isEmpty()) {
            graphics.text(font, "还没有对话。输入问题，TomeWisp 会先查证再回答。",
                    area.x() + 9, y, MUTED, false);
            y += 20;
        }
        for (GuideUiRow row : view.rows()) {
            y = renderRow(graphics, row, area.x() + 9, y, textWidth);
        }
        contentHeight = Math.max(0, y + scroll - area.y());
        graphics.disableScissor();
    }

    private int renderRow(GuiGraphicsExtractor graphics, GuideUiRow row, int x, int y, int width) {
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
                y = renderWrapped(graphics, GuideMarkup.paragraphs(assistant.text()), x + 6, y, width - 6, TEXT);
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
            int color = activity.status() == GuideToolStatus.FAILED ? ERROR : 0xFF7FC8A9;
            String icon = switch (activity.status()) {
                case RUNNING -> "◌";
                case SUCCEEDED -> "✓";
                case FAILED -> "!";
            };
            graphics.fill(x + 2, y - 2, x + width - 2, y + 15, PANEL_ALT);
            graphics.text(font, icon + " " + friendlyTool(activity.toolId()), x + 7, y + 2, color, false);
            hits.add(new Hit(new GuideUiLayout.Rect(x + 2, y - 2, width - 4, 17),
                    HitKind.CONTENT, () -> open(tool)));
            return y + 21;
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
                for (String line : toolDetail.narration()) {
                    y = detailLine(graphics, line, detail, y);
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
        for (FormattedCharSequence line : font.split(Component.literal(text), detail.width() - 16)) {
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
        draft = composer == null ? draft : composer.getValue();
        rebuildWidgets();
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

    private void refreshDisplayProjection() {
        GuideDisplayConfig nextDisplay = currentDisplay();
        if (nextDisplay.equals(projectedDisplay)) return;
        GuideUiView next = GuideUiView.from(service.snapshot(), nextDisplay);
        refreshDetail(next);
        projectedDisplay = nextDisplay;
        view = next;
        updateControls();
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
                : Component.literal(choice.displayName());
    }

    static String sourceLabel(GuideSource source, boolean debugMode) {
        if (!debugMode) {
            return Component.translatable("screen.tomewisp.detail.source_link").getString();
        }
        return "source · " + source.evidence().sourceId() + " · "
                + source.evidence().authority() + "/" + source.evidence().completeness();
    }

    private static String friendlyTool(String id) {
        int separator = id.indexOf(':');
        String name = separator >= 0 ? id.substring(separator + 1) : id;
        return switch (name) {
            case "search_recipes" -> "搜索配方";
            case "get_recipe" -> "读取完整配方";
            case "find_item_usages" -> "查询物品用途";
            case "inspect_inventory" -> "检查库存";
            case "calculate_craftability" -> "计算可合成性";
            case "search_knowledge" -> "搜索指南";
            case "load_knowledge" -> "读取指南";
            case "load_skill" -> "加载 Skill";
            default -> name;
        };
    }

    private enum HitKind { SESSION, CONTENT, DETAIL }
    private record Hit(GuideUiLayout.Rect rect, HitKind kind, Runnable action) {}
}
