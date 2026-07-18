package dev.tomewisp.client.gui;

import dev.tomewisp.client.gui.settings.ModelProfileDraft;
import dev.tomewisp.client.gui.settings.SettingsLayout;
import dev.tomewisp.client.gui.settings.SettingsSection;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.settings.ClientSettingsService;
import dev.tomewisp.settings.ClientSettingsSnapshot;
import dev.tomewisp.settings.SettingsNotice;
import dev.tomewisp.settings.SettingsOperation;
import dev.tomewisp.settings.model.ModelConnectionResult;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native settings shell and model-profile editor backed only by ClientSettingsService. */
public final class TomeWispSettingsScreen extends Screen {
    private static final int BACKGROUND = 0xE00B0D12;
    private static final int PANEL = 0xE0181B22;
    private static final int PANEL_ALT = 0xE0242933;
    private static final int ACCENT = 0xFF72D5C4;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFFA9B3BE;
    private static final int ERROR = 0xFFFF7D7D;

    private final ClientSettingsService service;
    private final Runnable returnToGuide;
    private volatile ClientSettingsSnapshot snapshot;
    private AutoCloseable listener;
    private SettingsLayout layout;
    private SettingsSection section = SettingsSection.MODELS;
    private String selectedProfileId;
    private ModelProfileDraft draft;
    private Confirmation confirmation = Confirmation.NONE;
    private String localNotice = "";
    private boolean draftEnabled;
    private ModelProtocol draftProtocol;
    private int editorScroll;
    private EditBox id;
    private EditBox displayName;
    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKeyEnv;
    private EditBox contextWindow;
    private EditBox maxOutput;
    private EditBox connectTimeout;
    private EditBox requestTimeout;

    public TomeWispSettingsScreen(
            ClientSettingsService service,
            Runnable returnToGuide) {
        super(Component.translatable("screen.tomewisp.settings.title"));
        this.service = Objects.requireNonNull(service, "service");
        this.returnToGuide = Objects.requireNonNull(returnToGuide, "returnToGuide");
        this.snapshot = service.snapshot();
        select(snapshot.models().config().defaultProfileId());
    }

    @Override
    protected void init() {
        layout = SettingsLayout.calculate(width, height);
        addHeaderActions();
        if (layout.wide()) {
            addSectionNavigation();
        }
        if (section == SettingsSection.MODELS) {
            addModelsPage();
        }
        addFooterActions();
    }

    @Override
    public void added() {
        listener = service.listen(next -> {
            if (layout != null) {
                captureDraft();
            }
            ClientSettingsSnapshot previous = snapshot;
            snapshot = next;
            if (!previous.models().config().equals(next.models().config())) {
                String retained = next.models().profiles().stream()
                        .map(profile -> profile.definition().id())
                        .filter(profileId -> profileId.equals(selectedProfileId))
                        .findFirst()
                        .orElse(next.models().config().defaultProfileId());
                select(retained);
                confirmation = Confirmation.NONE;
            }
            if (layout != null) {
                rebuildWidgets();
            }
        });
    }

    @Override
    public void removed() {
        service.cancelConnectionTest();
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception ignored) {
                // Detaching a local listener has no recovery action.
            }
            listener = null;
        }
    }

    @Override
    protected void repositionElements() {
        captureDraft();
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        returnToGuide.run();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY) {
        if (section == SettingsSection.MODELS && layout.editor().contains(mouseX, mouseY)) {
            captureDraft();
            int viewport = Math.max(1, layout.editor().height() - 38);
            int maximum = Math.max(0, 206 - viewport);
            editorScroll = net.minecraft.util.Mth.clamp(
                    editorScroll - (int) Math.round(scrollY * 22), 0, maximum);
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        panel(graphics, layout.header(), PANEL);
        panel(graphics, layout.content(), PANEL);
        panel(graphics, layout.footer(), PANEL_ALT);
        graphics.text(font, title, layout.header().x() + 8, layout.header().y() + 9, TEXT, false);
        if (layout.wide()) {
            panel(graphics, layout.navigation(), PANEL_ALT);
        }
        if (section == SettingsSection.MODELS) {
            renderModels(graphics);
        } else {
            renderPlaceholder(graphics);
        }
        renderNotice(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void addHeaderActions() {
        int y = layout.header().y() + 4;
        if (layout.showBack()) {
            int backX = layout.header().right() - 62;
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.tomewisp.settings.back"),
                            ignored -> onClose())
                    .bounds(backX, y, 56, 20)
                    .build());
            int sectionX = layout.header().x() + 90;
            addRenderableWidget(Button.builder(
                            Component.translatable(section.translationKey()),
                            ignored -> cycleSection())
                    .bounds(sectionX, y, Math.max(50, backX - sectionX - 4), 20)
                    .build());
        }
    }

    private void addSectionNavigation() {
        int x = layout.navigation().x() + 6;
        int y = layout.navigation().y() + 8;
        int buttonWidth = layout.navigation().width() - 12;
        for (SettingsSection candidate : SettingsSection.topLevel()) {
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable(candidate.translationKey()),
                            ignored -> switchSection(candidate))
                    .bounds(x, y, buttonWidth, 20)
                    .build());
            button.active = candidate != section;
            y += 24;
        }
    }

    private void addModelsPage() {
        if (layout.wide()) {
            addProfileList();
        }
        addEditor();
    }

    private void addProfileList() {
        int x = layout.list().x() + 6;
        int y = layout.list().y() + 26;
        int buttonWidth = layout.list().width() - 12;
        for (ModelCard card : project(snapshot).models()) {
            Component label = Component.literal(
                    (card.defaultProfile() ? "★ " : "") + card.displayName());
            Button button = addRenderableWidget(Button.builder(
                            label,
                            ignored -> selectAndRebuild(card.id()))
                    .bounds(x, y, buttonWidth, 22)
                    .build());
            button.active = !card.id().equals(selectedProfileId);
            y += 26;
            if (y > layout.list().bottom() - 50) {
                break;
            }
        }
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.settings.models.add"),
                        ignored -> createProfile())
                .bounds(x, layout.list().bottom() - 28, buttonWidth, 20)
                .build());
    }

    private void addEditor() {
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 8;
        int y = area.y() + 8;
        int editorWidth = Math.max(100, area.width() - 16);
        int toggleWidth = Math.min(118, Math.max(70, (editorWidth - 8) / 2));
        addRenderableWidget(Button.builder(protocolLabel(), ignored -> cycleProtocol())
                .bounds(x, y, toggleWidth, 20).build());
        addRenderableWidget(Button.builder(enabledLabel(), ignored -> toggleEnabled())
                .bounds(x + toggleWidth + 6, y, toggleWidth, 20).build());
        y += 32 - editorScroll;
        int labelWidth = Math.min(104, Math.max(72, editorWidth / 3));
        int inputX = x + labelWidth;
        int inputWidth = Math.max(70, editorWidth - labelWidth);
        id = field(inputX, y, inputWidth, "screen.tomewisp.settings.models.id", draft.id());
        y += 22;
        displayName = field(
                inputX, y, inputWidth, "screen.tomewisp.settings.models.name", draft.displayName());
        y += 22;
        baseUrl = field(
                inputX, y, inputWidth, "screen.tomewisp.settings.models.base_url", draft.baseUrl());
        y += 22;
        model = field(
                inputX, y, inputWidth, "screen.tomewisp.settings.models.model_id", draft.model());
        y += 22;
        apiKeyEnv = field(
                inputX, y, inputWidth, "screen.tomewisp.settings.models.api_key_env", draft.apiKeyEnv());
        y += 22;
        contextWindow = field(
                inputX,
                y,
                inputWidth,
                "screen.tomewisp.settings.models.context_window",
                draft.contextWindowTokens());
        y += 22;
        maxOutput = field(
                inputX, y, inputWidth, "screen.tomewisp.settings.models.max_output", draft.maxOutputTokens());
        y += 22;
        connectTimeout = field(
                inputX,
                y,
                inputWidth,
                "screen.tomewisp.settings.models.connect_timeout",
                draft.connectTimeoutSeconds());
        y += 22;
        requestTimeout = field(
                inputX,
                y,
                inputWidth,
                "screen.tomewisp.settings.models.request_timeout",
                draft.requestTimeoutSeconds());
    }

    private EditBox field(int x, int y, int width, String narrationKey, String value) {
        EditBox field = new EditBox(
                font, x, y, width, 18, Component.translatable(narrationKey));
        field.setValue(value == null ? "" : value);
        field.setMaxLength(2048);
        field.setResponder(ignored -> confirmation = Confirmation.NONE);
        field.setVisible(y >= layout.editor().y() + 30
                && y + 18 <= layout.editor().bottom());
        return addRenderableWidget(field);
    }

    private void addFooterActions() {
        List<Action> actions = section == SettingsSection.MODELS
                ? List.of(
                        new Action("screen.tomewisp.settings.save", this::save),
                        new Action(reloadKey(), this::reload),
                        new Action(deleteKey(), this::delete),
                        new Action("screen.tomewisp.settings.models.default", this::makeDefault),
                        new Action(testKey(), this::testConnection),
                        new Action("screen.tomewisp.settings.cancel", this::cancel),
                        new Action("screen.tomewisp.settings.models.refresh", this::refreshMetadata),
                        new Action("screen.tomewisp.settings.done", this::onClose))
                : List.of(new Action("screen.tomewisp.settings.done", this::onClose));
        int gap = 4;
        int columns = Math.min(4, actions.size());
        int available = layout.footer().width() - 12;
        int buttonWidth = Math.max(34, (available - gap * (columns - 1)) / columns);
        for (int index = 0; index < actions.size(); index++) {
            Action action = actions.get(index);
            int column = index % columns;
            int row = index / columns;
            int x = layout.footer().x() + 6 + column * (buttonWidth + gap);
            int y = layout.footer().y() + 4 + row * 23;
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable(action.translationKey()),
                            ignored -> action.action().run())
                    .bounds(x, y, buttonWidth, 20)
                    .build());
            button.active = actionEnabled(action.translationKey());
        }
    }

    private boolean actionEnabled(String key) {
        boolean busy = snapshot.operation().kind() != SettingsOperation.Kind.IDLE;
        if (key.equals("screen.tomewisp.settings.cancel")) {
            return snapshot.operation().kind() == SettingsOperation.Kind.TESTING_CONNECTION;
        }
        if (key.equals("screen.tomewisp.settings.done")) {
            return true;
        }
        return !busy;
    }

    private void renderModels(GuiGraphicsExtractor graphics) {
        if (layout.wide()) {
            graphics.text(
                    font,
                    Component.translatable("screen.tomewisp.settings.models.profiles"),
                    layout.list().x() + 8,
                    layout.list().y() + 9,
                    ACCENT,
                    false);
        }
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 8;
        int y = area.y() + 43 - editorScroll;
        String[] labels = {
            "screen.tomewisp.settings.models.id",
            "screen.tomewisp.settings.models.name",
            "screen.tomewisp.settings.models.base_url",
            "screen.tomewisp.settings.models.model_id",
            "screen.tomewisp.settings.models.api_key_env",
            "screen.tomewisp.settings.models.context_window",
            "screen.tomewisp.settings.models.max_output",
            "screen.tomewisp.settings.models.connect_timeout",
            "screen.tomewisp.settings.models.request_timeout"
        };
        for (String label : labels) {
            if (y >= area.y() + 30 && y + 18 <= area.bottom()) {
                graphics.text(font, Component.translatable(label), x, y + 5, MUTED, false);
            }
            y += 22;
        }
        int statusY = Math.min(area.bottom() - 13, y + 3);
        selectedView().ifPresent(profile -> {
            int color = profile.available() ? 0xFF7FC8A9 : 0xFFFFD479;
            Component status = Component.translatable(
                    profile.available()
                            ? "screen.tomewisp.settings.models.available"
                            : "screen.tomewisp.settings.models.unavailable",
                    profile.definition().apiKeyEnv());
            graphics.text(font, status, x, statusY, color, false);
        });
    }

    private void renderPlaceholder(GuiGraphicsExtractor graphics) {
        SettingsLayout.Rect area = layout.editor();
        graphics.text(
                font,
                Component.translatable(section.translationKey()),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        graphics.text(
                font,
                Component.translatable("screen.tomewisp.settings.section_pending"),
                area.x() + 10,
                area.y() + 31,
                MUTED,
                false);
    }

    private void renderNotice(GuiGraphicsExtractor graphics) {
        String message = localNotice;
        int color = ERROR;
        SettingsNotice serviceNotice = snapshot.notice();
        if (message.isBlank() && serviceNotice != null) {
            message = serviceNotice.message();
            color = serviceNotice.level() == SettingsNotice.Level.SUCCESS ? 0xFF7FC8A9 : ERROR;
        }
        if (message.isBlank()) {
            return;
        }
        graphics.text(
                font,
                message,
                layout.header().x() + 150,
                layout.header().y() + 10,
                color,
                false);
    }

    private void save() {
        captureDraft();
        ToolResult<ModelProfileDefinition> validated = draft.validate();
        if (validated instanceof ToolResult.Failure<ModelProfileDefinition> failure) {
            localNotice = failure.message();
            return;
        }
        ModelProfileDefinition definition =
                ((ToolResult.Success<ModelProfileDefinition>) validated).value();
        ModelProfilesConfig candidate;
        try {
            candidate = candidateWith(definition);
        } catch (RuntimeException failure) {
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.models.invalid").getString();
            return;
        }
        selectedProfileId = definition.id();
        accept(service.saveModels(candidate));
    }

    private void reload() {
        if (confirmation != Confirmation.RELOAD) {
            confirmation = Confirmation.RELOAD;
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.confirm_reload").getString();
            rebuildWidgets();
            return;
        }
        confirmation = Confirmation.NONE;
        accept(service.reloadModels(true));
    }

    private void delete() {
        if (selectedProfileId == null) {
            select(snapshot.models().config().defaultProfileId());
            rebuildWidgets();
            return;
        }
        if (snapshot.models().config().profiles().size() <= 1) {
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.models.cannot_delete_last").getString();
            return;
        }
        if (confirmation != Confirmation.DELETE) {
            confirmation = Confirmation.DELETE;
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.confirm_delete").getString();
            rebuildWidgets();
            return;
        }
        List<ModelProfileDefinition> retained = snapshot.models().config().profiles().stream()
                .filter(profile -> !profile.id().equals(selectedProfileId))
                .toList();
        String defaultId = snapshot.models().config().defaultProfileId().equals(selectedProfileId)
                ? retained.getFirst().id()
                : snapshot.models().config().defaultProfileId();
        select(retained.getFirst().id());
        confirmation = Confirmation.NONE;
        accept(service.saveModels(new ModelProfilesConfig(1, defaultId, retained)));
    }

    private void makeDefault() {
        if (selectedProfileId == null) {
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.models.save_first").getString();
            return;
        }
        ModelProfilesConfig current = snapshot.models().config();
        accept(service.saveModels(new ModelProfilesConfig(
                current.schemaVersion(), selectedProfileId, current.profiles())));
    }

    private void testConnection() {
        captureDraft();
        ToolResult<ModelProfileDefinition> validated = draft.validate();
        if (validated instanceof ToolResult.Failure<ModelProfileDefinition> failure) {
            localNotice = failure.message();
            return;
        }
        if (confirmation != Confirmation.TEST_CONNECTION) {
            confirmation = Confirmation.TEST_CONNECTION;
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.confirm_billable_test").getString();
            rebuildWidgets();
            return;
        }
        confirmation = Confirmation.NONE;
        ModelProfileDefinition definition =
                ((ToolResult.Success<ModelProfileDefinition>) validated).value();
        service.testConnection(definition).thenAccept(result -> {
            if (result instanceof ModelConnectionResult.Failure failure) {
                localNotice = failure.message();
            } else {
                localNotice = "";
            }
        });
    }

    private void cancel() {
        service.cancelConnectionTest();
    }

    private void refreshMetadata() {
        accept(service.refreshMetadata());
    }

    private void accept(java.util.concurrent.CompletableFuture<ToolResult<Boolean>> future) {
        future.thenAccept(result -> {
            if (result instanceof ToolResult.Failure<Boolean> failure) {
                localNotice = failure.message();
            } else {
                localNotice = "";
            }
        });
    }

    private ModelProfilesConfig candidateWith(ModelProfileDefinition replacement) {
        ModelProfilesConfig current = snapshot.models().config();
        List<ModelProfileDefinition> profiles = new ArrayList<>();
        boolean replaced = false;
        for (ModelProfileDefinition profile : current.profiles()) {
            if (selectedProfileId != null && profile.id().equals(selectedProfileId)) {
                profiles.add(replacement);
                replaced = true;
            } else {
                profiles.add(profile);
            }
        }
        if (!replaced) {
            profiles.add(replacement);
        }
        String defaultId = current.defaultProfileId();
        if (selectedProfileId != null
                && defaultId.equals(selectedProfileId)
                && !selectedProfileId.equals(replacement.id())) {
            defaultId = replacement.id();
        }
        return new ModelProfilesConfig(current.schemaVersion(), defaultId, profiles);
    }

    private void switchSection(SettingsSection replacement) {
        captureDraft();
        section = replacement;
        editorScroll = 0;
        confirmation = Confirmation.NONE;
        localNotice = "";
        rebuildWidgets();
    }

    private void selectAndRebuild(String profileId) {
        select(profileId);
        editorScroll = 0;
        confirmation = Confirmation.NONE;
        localNotice = "";
        editorScroll = 0;
        rebuildWidgets();
    }

    private void select(String profileId) {
        ModelProfileDefinition definition = snapshot.models().config().profiles().stream()
                .filter(profile -> profile.id().equals(profileId))
                .findFirst()
                .orElseThrow();
        selectedProfileId = definition.id();
        draft = ModelProfileDraft.from(definition);
        draftEnabled = definition.enabled();
        draftProtocol = definition.protocol();
    }

    private void createProfile() {
        int suffix = 1;
        String candidate = "profile-" + suffix;
        while (containsProfile(candidate)) {
            candidate = "profile-" + ++suffix;
        }
        selectedProfileId = null;
        draft = ModelProfileDraft.create(candidate);
        draftEnabled = true;
        draftProtocol = ModelProtocol.OPENAI_CHAT;
        confirmation = Confirmation.NONE;
        localNotice = "";
        rebuildWidgets();
    }

    private boolean containsProfile(String profileId) {
        return snapshot.models().config().profiles().stream()
                .anyMatch(profile -> profile.id().equals(profileId));
    }

    private void captureDraft() {
        if (id == null) {
            return;
        }
        draft = new ModelProfileDraft(
                id.getValue(),
                displayName.getValue(),
                draftEnabled,
                draftProtocol,
                baseUrl.getValue(),
                model.getValue(),
                apiKeyEnv.getValue(),
                contextWindow.getValue(),
                maxOutput.getValue(),
                connectTimeout.getValue(),
                requestTimeout.getValue(),
                draft.metadata());
    }

    private void cycleProtocol() {
        captureDraft();
        draftProtocol = draftProtocol == ModelProtocol.OPENAI_CHAT
                ? ModelProtocol.ANTHROPIC_MESSAGES
                : ModelProtocol.OPENAI_CHAT;
        confirmation = Confirmation.NONE;
        rebuildWidgets();
    }

    private void toggleEnabled() {
        captureDraft();
        draftEnabled = !draftEnabled;
        confirmation = Confirmation.NONE;
        rebuildWidgets();
    }

    private void cycleSection() {
        List<SettingsSection> sections = SettingsSection.topLevel();
        switchSection(sections.get((sections.indexOf(section) + 1) % sections.size()));
    }

    private Component protocolLabel() {
        return Component.translatable(
                draftProtocol == ModelProtocol.OPENAI_CHAT
                        ? "screen.tomewisp.settings.models.protocol_openai"
                        : "screen.tomewisp.settings.models.protocol_anthropic");
    }

    private Component enabledLabel() {
        return Component.translatable(
                draftEnabled
                        ? "screen.tomewisp.settings.models.enabled"
                        : "screen.tomewisp.settings.models.disabled");
    }

    private String reloadKey() {
        return confirmation == Confirmation.RELOAD
                ? "screen.tomewisp.settings.confirm"
                : "screen.tomewisp.settings.reload";
    }

    private String deleteKey() {
        return confirmation == Confirmation.DELETE
                ? "screen.tomewisp.settings.confirm"
                : "screen.tomewisp.settings.delete";
    }

    private String testKey() {
        return confirmation == Confirmation.TEST_CONNECTION
                ? "screen.tomewisp.settings.confirm"
                : "screen.tomewisp.settings.models.test";
    }

    private java.util.Optional<ModelProfileSettingsView.Profile> selectedView() {
        return snapshot.models().profiles().stream()
                .filter(profile -> profile.definition().id().equals(selectedProfileId))
                .findFirst();
    }

    private static void panel(
            GuiGraphicsExtractor graphics, SettingsLayout.Rect rect, int color) {
        if (rect.width() > 0 && rect.height() > 0) {
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color);
        }
    }

    static Projection project(ClientSettingsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<ModelCard> cards = snapshot.models().profiles().stream()
                .map(profile -> new ModelCard(
                        profile.definition().id(),
                        profile.definition().displayName(),
                        profile.definition().model(),
                        profile.definition().apiKeyEnv(),
                        profile.credentialPresent(),
                        profile.available(),
                        profile.definition().id().equals(
                                snapshot.models().config().defaultProfileId()),
                        profile.failure() == null ? null : profile.failure().code()))
                .toList();
        return new Projection(
                SettingsSection.topLevel(), cards, snapshot.operation(), snapshot.notice());
    }

    record Projection(
            List<SettingsSection> sections,
            List<ModelCard> models,
            SettingsOperation operation,
            SettingsNotice notice) {
        Projection {
            sections = List.copyOf(sections);
            models = List.copyOf(models);
        }
    }

    record ModelCard(
            String id,
            String displayName,
            String model,
            String credentialEnvironment,
            boolean credentialPresent,
            boolean available,
            boolean defaultProfile,
            String failureCode) {}

    private record Action(String translationKey, Runnable action) {}

    private enum Confirmation {
        NONE,
        RELOAD,
        DELETE,
        TEST_CONNECTION
    }
}
