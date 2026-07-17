package dev.tomewisp.client.gui;

import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideService;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideSubscription;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.ui.GuideUiLayout;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.guide.ui.GuideUiSession;
import dev.tomewisp.guide.ui.GuideUiView;
import dev.tomewisp.guide.ui.GuideToolPresenter;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
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
    private volatile GuideUiView view;
    private GuideSubscription subscription;
    private GuideUiLayout layout;
    private MultiLineEditBox composer;
    private Button send;
    private Button stop;
    private Button retry;
    private Button mode;
    private String draft = "";
    private String notice = "";
    private int scroll;
    private int contentHeight;
    private boolean sessionOverlay;
    private GuideToolActivity selectedTool;
    private GuideSource selectedSource;
    private final List<Hit> hits = new ArrayList<>();

    public TomeWispScreen(GuideService service) {
        super(Component.translatable("screen.tomewisp.guide"));
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.view = GuideUiView.from(service.snapshot());
    }

    @Override
    protected void init() {
        layout = GuideUiLayout.calculate(width, height, detailOpen());
        GuideUiLayout.Rect top = layout.topBar();
        int x = top.x() + top.width() - 176;
        addRenderableWidget(Button.builder(Component.literal("会话"), button -> sessionOverlay = !sessionOverlay)
                .bounds(x, top.y() + 2, 32, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> createSession())
                .bounds(x + 36, top.y() + 2, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> closeSession())
                .bounds(x + 64, top.y() + 2, 32, 20).build());
        mode = addRenderableWidget(Button.builder(modeLabel(), button -> toggleMode())
                .bounds(x + 100, top.y() + 2, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> service.refreshCapabilities())
                .bounds(x + 156, top.y() + 2, 20, 20).build());

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
            GuideUiView next = GuideUiView.from(snapshot);
            boolean changedSession = !view.selectedSession().equals(next.selectedSession());
            boolean closedDetail = refreshDetail(next);
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
            if (detailOpen() && layout.detail().contains(event.x(), event.y())) {
                selectedTool = null;
                selectedSource = null;
                rebuildForDetail();
                return true;
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
        graphics.fill(0, 0, width, height, 0xC00B0D12);
        renderTop(graphics);
        renderSessions(graphics);
        renderTranscript(graphics);
        renderDetail(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void renderTop(GuiGraphicsExtractor graphics) {
        GuideUiLayout.Rect top = layout.topBar();
        graphics.fill(top.x(), top.y(), top.x() + top.width(), top.y() + top.height(), PANEL);
        graphics.text(font, Component.literal("TomeWisp 书灵").withStyle(ChatFormatting.BOLD),
                top.x() + 8, top.y() + 6, TEXT);
        graphics.text(font, view.capabilityMessage(), top.x() + 8, top.y() + 25, MUTED, false);
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
                String label = "来源 · " + source.evidence().sourceId() + " · "
                        + source.evidence().authority() + "/" + source.evidence().completeness();
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
                    HitKind.CONTENT, () -> open(activity)));
            return y + 21;
        }
        GuideUiRow.Status status = (GuideUiRow.Status) row;
        int color = status.status() == GuideRequestStatus.RATE_LIMITED ? 0xFFFFD479 : ERROR;
        graphics.text(font, status.text(), x + 6, y, color, false);
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

    private void renderDetail(GuiGraphicsExtractor graphics) {
        if (!detailOpen()) return;
        GuideUiLayout.Rect detail = layout.detail();
        graphics.fill(detail.x(), detail.y(), detail.x() + detail.width(), detail.y() + detail.height(), 0xF02A303A);
        graphics.outline(detail.x(), detail.y(), detail.width(), detail.height(), ACCENT);
        graphics.text(font, "详情（点击面板关闭）", detail.x() + 8, detail.y() + 8, ACCENT, false);
        int y = detail.y() + 24;
        if (selectedTool != null) {
            y = detailLine(graphics, "工具: " + selectedTool.toolId(), detail, y);
            y = detailLine(graphics, "状态: " + selectedTool.status(), detail, y);
            List<String> presentation = selectedTool.presentationLines().isEmpty()
                    && selectedTool.normalized() != null
                    ? GuideToolPresenter.lines(selectedTool.toolId(), selectedTool.normalized())
                    : selectedTool.presentationLines();
            for (String line : presentation) {
                y = detailLine(graphics, line, detail, y);
            }
            for (GuideSource source : selectedTool.sources()) {
                y = evidence(graphics, source, detail, y);
            }
        } else if (selectedSource != null) {
            y = evidence(graphics, selectedSource, detail, y);
        }
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
            if (y < detail.y() + detail.height() - 10) graphics.text(font, line, detail.x() + 8, y, TEXT, false);
            y += 10;
        }
        return y + 2;
    }

    private void open(GuideToolActivity activity) {
        selectedTool = activity;
        selectedSource = null;
        rebuildForDetail();
    }

    private void open(GuideSource source) {
        selectedSource = source;
        selectedTool = null;
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
            GuideToolActivity replacement = next.rows().stream()
                    .filter(GuideUiRow.Tool.class::isInstance)
                    .map(GuideUiRow.Tool.class::cast)
                    .map(GuideUiRow.Tool::activity)
                    .filter(value -> value.invocationId().equals(selectedTool.invocationId()))
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
            return true;
        }
        return false;
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
                        || value.status() == GuideRequestStatus.CANCELLED)
                .reduce((first, second) -> second).orElse(null);
        if (request != null) accept(service.retry(request.requestId()), ignored -> notice = "");
    }

    private void toggleMode() {
        GuideModelMode target = view.modelMode() == GuideModelMode.CLIENT
                ? GuideModelMode.SERVER : GuideModelMode.CLIENT;
        accept(service.setModelMode(target), ignored -> notice = "");
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
        mode.setMessage(modeLabel());
        mode.active = view.modelMode() == GuideModelMode.CLIENT
                ? view.serverModelAvailable()
                : view.clientModelAvailable();
    }

    private Component modeLabel() {
        return Component.literal(view.modelMode() == GuideModelMode.CLIENT ? "本地" : "服务");
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

    private enum HitKind { SESSION, CONTENT }
    private record Hit(GuideUiLayout.Rect rect, HitKind kind, Runnable action) {}
}
