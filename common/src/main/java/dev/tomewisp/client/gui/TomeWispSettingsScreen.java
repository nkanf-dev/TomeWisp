package dev.tomewisp.client.gui;

import dev.tomewisp.client.gui.settings.DiagnosticsSettingsProjection;
import dev.tomewisp.client.gui.settings.GeneralSettingsProjection;
import dev.tomewisp.client.gui.settings.HistorySettingsProjection;
import dev.tomewisp.client.gui.settings.ModelProfileDraft;
import dev.tomewisp.client.gui.settings.RecipeSettingsProjection;
import dev.tomewisp.client.gui.settings.SettingsLayout;
import dev.tomewisp.client.gui.settings.SettingsSection;
import dev.tomewisp.client.gui.settings.ToolSettingsProjection;
import dev.tomewisp.client.gui.settings.SkillSettingsProjection;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.recipe.RecipeVisibilityPolicy;
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.settings.ClientSettingsService;
import dev.tomewisp.settings.ClientSettingsSnapshot;
import dev.tomewisp.settings.SettingsNotice;
import dev.tomewisp.settings.SettingsOperation;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticCard;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsSnapshot;
import dev.tomewisp.settings.model.ModelConnectionResult;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
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
    private SettingsSection section = SettingsSection.GENERAL;
    private String selectedProfileId;
    private ModelProfileDraft draft;
    private Confirmation confirmation = Confirmation.NONE;
    private String localNotice = "";
    private boolean draftEnabled;
    private ModelProtocol draftProtocol;
    private int editorScroll;
    private RecipeClientConfig recipeDraft;
    private ToolFamilyId selectedTool = ToolFamilyId.RECIPES;
    private String selectedToolSourceId;
    private String selectedSkillName;
    private boolean skillEditing;
    private boolean narrowToolDetail;
    private boolean narrowSkillDetail;
    private String skillDraftMarkdown = "";
    private MultiLineEditBox skillEditor;
    private EditBox sourceDisplayName;
    private EditBox sourceDirectory;
    private EditBox sourceLocale;
    private int pageScroll;
    private int pageContentHeight;
    private ClientSettingsService.HistoryConfirmationToken historyConfirmation;
    private EditBox id;
    private EditBox displayName;
    private EditBox baseUrl;
    private EditBox model;
    private PasswordEditBox apiKey;
    private String pendingApiKey = "";
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
        recipeDraft = snapshot.recipes().config();
        selectedSkillName = snapshot.skills().skills().isEmpty()
                ? null
                : snapshot.skills().skills().getFirst().metadata().name();
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
        } else if (section == SettingsSection.TOOLS) {
            addKnowledgePage();
        } else if (section == SettingsSection.SKILLS) {
            addSkillsPage();
        } else if (section == SettingsSection.GENERAL) {
            addGeneralPage();
        } else if (section == SettingsSection.HISTORY) {
            addHistoryPage();
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
            if (previous.generation() != next.generation()) {
                historyConfirmation = null;
            }
            if (!previous.models().config().equals(next.models().config())
                    || completedReload(
                            previous, next, SettingsOperation.Kind.RELOADING_MODELS)) {
                String retained = next.models().profiles().stream()
                        .map(profile -> profile.definition().id())
                        .filter(profileId -> profileId.equals(selectedProfileId))
                        .findFirst()
                        .orElse(next.models().config().defaultProfileId());
                select(retained);
                confirmation = Confirmation.NONE;
            }
            if (!previous.recipes().config().equals(next.recipes().config())
                    || completedReload(
                            previous, next, SettingsOperation.Kind.RELOADING_RECIPES)) {
                recipeDraft = next.recipes().config();
                confirmation = Confirmation.NONE;
            }
            if (!previous.skills().equals(next.skills())) {
                if (selectedSkillName == null || next.skills().find(selectedSkillName).isEmpty()) {
                    selectedSkillName = next.skills().skills().isEmpty()
                            ? null
                            : next.skills().skills().getFirst().metadata().name();
                }
                skillEditing = false;
                skillDraftMarkdown = "";
            }
            if (layout != null) {
                rebuildWidgets();
            }
        });
    }

    @Override
    public void removed() {
        service.cancelConnectionTest();
        historyConfirmation = null;
        narrowToolDetail = false;
        narrowSkillDetail = false;
        skillEditing = false;
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
    public void tick() {
        super.tick();
        service.refreshRuntimeState();
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
        if ((section == SettingsSection.DIAGNOSTICS
                        || section == SettingsSection.HISTORY)
                && layout.content().contains(mouseX, mouseY)) {
            int maximum = Math.max(0, pageContentHeight - layout.content().height() + 18);
            pageScroll = net.minecraft.util.Mth.clamp(
                    pageScroll - (int) Math.round(scrollY * 24), 0, maximum);
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
        } else if (section == SettingsSection.TOOLS) {
            renderKnowledge(graphics);
        } else if (section == SettingsSection.SKILLS) {
            renderSkills(graphics);
        } else if (section == SettingsSection.GENERAL) {
            renderGeneral(graphics);
        } else if (section == SettingsSection.HISTORY) {
            renderHistory(graphics);
        } else if (section == SettingsSection.DIAGNOSTICS) {
            renderDiagnostics(graphics);
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
                            ignored -> backOrClose())
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

    private void addGeneralPage() {
        GeneralSettingsProjection general = project(snapshot).general();
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 10;
        int y = area.y() + 42;
        int width = Math.min(280, Math.max(120, area.width() - 20));
        Button debug = addRenderableWidget(Button.builder(
                        Component.translatable(
                                general.debugLabelKey()).copy().append(" · ")
                                .append(Component.translatable(general.debugStatusKey())),
                        ignored -> accept(service.saveDisplay(general.toggleDebug())))
                .bounds(x, y, width, 22)
                .build());
        debug.setTooltip(Tooltip.create(
                Component.translatable(general.debugDescriptionKey())));
        debug.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        Button animations = addRenderableWidget(Button.builder(
                        Component.translatable(
                                general.animationsLabelKey()).copy().append(" · ")
                                .append(Component.translatable(general.animationsStatusKey())),
                        ignored -> accept(service.saveDisplay(general.toggleAnimations())))
                .bounds(x, y + 30, width, 22)
                .build());
        animations.setTooltip(Tooltip.create(
                Component.translatable(general.animationsDescriptionKey())));
        animations.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
    }

    private void addHistoryPage() {
        HistorySettingsProjection history = project(snapshot).history();
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 10;
        int y = area.y() + 64 - pageScroll;
        int width = Math.min(360, Math.max(140, area.width() - 20));
        for (HistorySettingsProjection.ActionRow row : history.actions()) {
            Button button = Button.builder(
                            historyActionLabel(row),
                            ignored -> activateHistory(row.action()))
                    .bounds(x, y, width, 22)
                    .build();
            button.setTooltip(Tooltip.create(Component.translatable(row.descriptionKey())));
            button.active = row.enabled();
            if (y >= area.y() + 48 && y + 22 <= area.bottom() - 4) {
                addRenderableWidget(button);
            }
            y += 30;
        }
        pageContentHeight = Math.max(0, y + pageScroll - area.y());
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

    private void addKnowledgePage() {
        if (layout.wide() || !narrowToolDetail) {
            addToolList();
        }
        if (layout.wide() || narrowToolDetail) {
            addToolDetail();
        }
    }

    private void addToolList() {
        SettingsLayout.Rect area = layout.wide() ? layout.list() : layout.content();
        int x = area.x() + 7;
        int y = area.y() + 28;
        int width = area.width() - 14;
        for (ToolSettingsProjection.Family family : toolProjection().families()) {
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable(family.titleKey()),
                            ignored -> {
                                selectedTool = family.id();
                                selectedToolSourceId = null;
                                narrowToolDetail = true;
                                rebuildWidgets();
                            })
                    .bounds(x, y, width, 22)
                    .build());
            button.active = family.id() != selectedTool;
            y += 26;
        }
    }

    private void addToolDetail() {
        ToolSettingsProjection.Family family = selectedToolFamily();
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 9;
        int y = area.y() + 48;
        int width = area.width() - 18;
        Button enable = addRenderableWidget(Button.builder(
                        Component.translatable(family.enabled()
                                ? "screen.tomewisp.settings.tools.disable"
                                : "screen.tomewisp.settings.tools.enable"),
                        ignored -> accept(service.saveToolSettings(
                                toolProjection().toggleTool(family.id()))))
                .bounds(x, y, Math.min(150, width), 22)
                .build());
        enable.active = family.available()
                && snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        y += 32;

        if (family.id() == ToolFamilyId.RECIPES) {
            int half = Math.max(80, (width - 4) / 2);
            addRenderableWidget(Button.builder(
                            Component.translatable(
                                    recipeDraft.visibility() == RecipeVisibilityPolicy.ALL_KNOWN
                                            ? "screen.tomewisp.settings.recipe.visibility_all"
                                            : "screen.tomewisp.settings.recipe.visibility_unlocked"),
                            ignored -> {
                                recipeDraft = recipeProjection().cycleVisibility();
                                accept(service.saveRecipeSettings(recipeDraft));
                            })
                    .bounds(x, y, half, 20)
                    .build());
            addRenderableWidget(Button.builder(
                            Component.translatable(
                                    "screen.tomewisp.settings.recipe.preferred",
                                    preferredViewerLabel()),
                            ignored -> {
                                recipeDraft = recipeProjection().cyclePreferredViewer();
                                accept(service.saveRecipeSettings(recipeDraft));
                            })
                    .bounds(x + half + 4, y, width - half - 4, 20)
                    .build());
            y += 30;
        }

        for (ToolSettingsProjection.Source source : family.sources()) {
            int toggleWidth = Math.min(76, Math.max(58, width / 4));
            Button select = addRenderableWidget(Button.builder(
                            Component.literal(source.displayName()),
                            ignored -> {
                                selectedToolSourceId = source.id();
                                rebuildWidgets();
                            })
                    .bounds(x, y, width - toggleWidth - 4, 20)
                    .build());
            select.active = !source.id().equals(selectedToolSourceId);
            Button toggle = addRenderableWidget(Button.builder(
                            Component.translatable(source.enabled()
                                    ? "screen.tomewisp.settings.capability.enabled"
                                    : "screen.tomewisp.settings.capability.disabled"),
                            ignored -> accept(service.saveToolSettings(
                                    toolProjection().toggleSource(family.id(), source.id()))))
                    .bounds(x + width - toggleWidth, y, toggleWidth, 20)
                    .build());
            toggle.active = source.available()
                    && snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
            y += 24;
        }

        if (family.id() == ToolFamilyId.GUIDES) {
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.tomewisp.settings.tools.source.add_local"),
                            ignored -> addLocalMarkdownSource())
                    .bounds(x, y + 2, Math.min(180, width), 20)
                    .build());
            y += 30;
        }
        int sourceEditorY = y;
        selectedToolSource().ifPresent(
                source -> addSourceEditor(source, x, sourceEditorY, width));
    }

    private void addSourceEditor(
            ToolSettingsProjection.Source source, int x, int y, int width) {
        if (!source.editable()) {
            return;
        }
        sourceDisplayName = field(
                x, y, width, "screen.tomewisp.settings.tools.source.name", source.displayName());
        y += 22;
        sourceDirectory = field(
                x,
                y,
                width,
                "screen.tomewisp.settings.tools.source.directory",
                source.config().get("directory").getAsString());
        y += 22;
        sourceLocale = field(
                x,
                y,
                width,
                "screen.tomewisp.settings.tools.source.locale",
                source.config().get("locale").getAsString());
        y += 24;
        int half = Math.max(60, (width - 4) / 2);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.settings.save"),
                        ignored -> saveSelectedSource())
                .bounds(x, y, half, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.tomewisp.settings.delete"),
                        ignored -> deleteSelectedSource())
                .bounds(x + half + 4, y, width - half - 4, 20)
                .build());
    }

    private void addSkillsPage() {
        SkillSettingsProjection projection = skillProjection();
        SettingsLayout.Rect listArea = layout.wide() ? layout.list() : layout.content();
        int x = listArea.x() + 7;
        int y = listArea.y() + 28;
        int width = listArea.width() - 14;
        if (layout.wide() || !narrowSkillDetail) for (SkillSettingsProjection.Skill skill : projection.skills()) {
            Component label = Component.literal(skill.name()).copy().append(" · ")
                    .append(Component.translatable(skill.localOverride()
                            ? "screen.tomewisp.settings.skills.local"
                            : "screen.tomewisp.settings.skills.bundled"));
            Button button = addRenderableWidget(Button.builder(label, ignored -> {
                        selectedSkillName = skill.name();
                        narrowSkillDetail = true;
                        skillEditing = false;
                        skillDraftMarkdown = "";
                        rebuildWidgets();
                    })
                    .bounds(x, y, width, 22)
                    .build());
            button.active = !skill.name().equals(selectedSkillName);
            y += 26;
        }

        if (layout.wide() || narrowSkillDetail) selectedSkill().ifPresent(skill -> {
            SettingsLayout.Rect area = layout.editor();
            int editorX = area.x() + 9;
            int editorY = area.y() + 58;
            int editorWidth = area.width() - 18;
            if (skillEditing) {
                skillEditor = MultiLineEditBox.builder()
                        .setX(editorX)
                        .setY(editorY)
                        .setPlaceholder(Component.translatable(
                                "screen.tomewisp.settings.skills.editor_placeholder"))
                        .build(
                                font,
                                editorWidth,
                                Math.max(70, area.bottom() - editorY - 34),
                                Component.translatable("screen.tomewisp.settings.skills.editor"));
                skillEditor.setValue(skillDraftMarkdown, true);
                skillEditor.setValueListener(value -> skillDraftMarkdown = value);
                addRenderableWidget(skillEditor);
                addRenderableWidget(Button.builder(
                                Component.translatable("screen.tomewisp.settings.save"),
                                ignored -> saveSkillOverride())
                        .bounds(editorX, area.bottom() - 26, Math.min(120, editorWidth), 20)
                        .build());
                addRenderableWidget(Button.builder(
                                Component.translatable("screen.tomewisp.settings.cancel"),
                                ignored -> {
                                    skillEditing = false;
                                    skillDraftMarkdown = "";
                                    rebuildWidgets();
                                })
                        .bounds(
                                editorX + Math.min(120, editorWidth) + 4,
                                area.bottom() - 26,
                                Math.min(100, Math.max(50, editorWidth - 124)),
                                20)
                        .build());
            } else {
                addRenderableWidget(Button.builder(
                                Component.translatable(skill.createsOverrideOnSave()
                                        ? "screen.tomewisp.settings.skills.create_override"
                                        : "screen.tomewisp.settings.skills.edit_override"),
                                ignored -> {
                                    skillEditing = true;
                                    skillDraftMarkdown = skill.markdown();
                                    rebuildWidgets();
                                })
                        .bounds(editorX, area.bottom() - 26, Math.min(160, editorWidth), 20)
                        .build());
                if (skill.canDeleteOverride()) {
                    addRenderableWidget(Button.builder(
                                    Component.translatable(
                                            "screen.tomewisp.settings.skills.delete_override"),
                                    ignored -> accept(service.deleteSkillOverride(skill.name())))
                            .bounds(
                                    editorX + Math.min(160, editorWidth) + 4,
                                    area.bottom() - 26,
                                    Math.min(140, Math.max(60, editorWidth - 164)),
                                    20)
                            .build());
                }
            }
        });
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
        apiKey = passwordField(inputX, y, inputWidth);
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

    private PasswordEditBox passwordField(int x, int y, int width) {
        PasswordEditBox field = new PasswordEditBox(
                font,
                x,
                y,
                width,
                18,
                Component.translatable("screen.tomewisp.settings.models.api_key"));
        field.setValue(pendingApiKey);
        field.setMaxLength(4096);
        field.setResponder(value -> {
            pendingApiKey = value;
            confirmation = Confirmation.NONE;
        });
        field.setVisible(y >= layout.editor().y() + 30
                && y + 18 <= layout.editor().bottom());
        return addRenderableWidget(field);
    }

    private void addFooterActions() {
        List<Action> actions = footerActions();
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

    private List<Action> footerActions() {
        return switch (section) {
            case MODELS -> List.of(
                    new Action("screen.tomewisp.settings.save", this::saveCurrent),
                    new Action(reloadKey(), this::reloadCurrent),
                    new Action(deleteKey(), this::delete),
                    new Action("screen.tomewisp.settings.models.default", this::makeDefault),
                    new Action(testKey(), this::testConnection),
                    new Action("screen.tomewisp.settings.cancel", this::cancel),
                    new Action("screen.tomewisp.settings.models.refresh", this::refreshMetadata),
                    new Action("screen.tomewisp.settings.done", this::onClose));
            case TOOLS -> knowledgeActions();
            case SKILLS -> List.of(
                    new Action(
                            "screen.tomewisp.settings.reload",
                            () -> accept(service.reloadSkills(true))),
                    new Action("screen.tomewisp.settings.done", this::onClose));
            case GENERAL -> List.of(
                    new Action(
                            "screen.tomewisp.settings.reload",
                            () -> accept(service.reloadDisplay())),
                    new Action("screen.tomewisp.settings.done", this::onClose));
            case HISTORY, DIAGNOSTICS ->
                    List.of(new Action("screen.tomewisp.settings.done", this::onClose));
        };
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
            "screen.tomewisp.settings.models.api_key",
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
                                    : "screen.tomewisp.settings.models.unavailable")
                    .copy().append(" · ")
                    .append(Component.translatable(pendingApiKey.isBlank()
                            ? (profile.credentialPresent()
                                    ? "screen.tomewisp.settings.models.api_key_saved"
                                    : "screen.tomewisp.settings.models.api_key_not_set")
                            : "screen.tomewisp.settings.models.api_key_replace"));
            graphics.text(font, status, x, statusY, color, false);
        });
    }

    private void renderGeneral(GuiGraphicsExtractor graphics) {
        GeneralSettingsProjection general = project(snapshot).general();
        SettingsLayout.Rect area = layout.editor();
        graphics.text(
                font,
                Component.translatable(general.titleKey()),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.translatable(general.debugDescriptionKey()),
                Math.max(80, area.width() - 20));
        int y = area.y() + 102;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.text(font, line, area.x() + 10, y, MUTED, false);
            y += 10;
        }
        y += 5;
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.translatable(general.animationsDescriptionKey()),
                Math.max(80, area.width() - 20))) {
            graphics.text(font, line, area.x() + 10, y, MUTED, false);
            y += 10;
        }
        pageContentHeight = y - area.y();
    }

    private void renderHistory(GuiGraphicsExtractor graphics) {
        HistorySettingsProjection history = project(snapshot).history();
        SettingsLayout.Rect area = layout.editor();
        graphics.text(
                font,
                Component.translatable(history.titleKey()),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        Component status = Component.translatable(history.scopeLabelKey())
                .copy().append(" · ")
                .append(Component.translatable(history.statusKey()));
        graphics.text(font, status, area.x() + 10, area.y() + 31, MUTED, false);
        int actionsBottom = area.y() + 64 - pageScroll + history.actions().size() * 30;
        pageContentHeight = Math.max(0, actionsBottom + pageScroll - area.y());
    }

    private void renderDiagnostics(GuiGraphicsExtractor graphics) {
        DiagnosticsSettingsProjection diagnostics = project(snapshot).diagnostics();
        SettingsLayout.Rect area = layout.editor();
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        int x = area.x() + 8;
        int width = Math.max(80, area.width() - 16);
        int y = area.y() + 10 - pageScroll;
        y = settingsHeading(
                graphics,
                Component.translatable(diagnostics.titleKey()),
                x,
                y,
                width,
                ACCENT);
        for (DiagnosticsSettingsProjection.CardRow card : diagnostics.cards()) {
            int cardHeight = 34 + (card.noteKeys().size() + card.metrics().size()) * 11;
            graphics.fill(x, y, x + width, y + cardHeight, PANEL_ALT);
            graphics.text(
                    font,
                    Component.literal(card.statusIcon() + " ")
                            .append(Component.translatable(card.titleKey())),
                    x + 7,
                    y + 6,
                    TEXT,
                    false);
            graphics.text(
                    font,
                    Component.translatable(card.statusTextKey()),
                    x + 7,
                    y + 18,
                    MUTED,
                    false);
            int metricY = y + 30;
            for (String noteKey : card.noteKeys()) {
                graphics.text(font, Component.translatable(noteKey), x + 12, metricY, MUTED, false);
                metricY += 11;
            }
            for (SettingsDiagnosticCard.Metric metric : card.metrics()) {
                graphics.text(
                        font,
                        Component.translatable(metric.labelKey(), metric.value()),
                        x + 12,
                        metricY,
                        MUTED,
                        false);
                metricY += 11;
            }
            y += cardHeight + 6;
        }
        if (diagnostics.debug().isPresent()) {
            y = renderDebugDiagnostics(
                    graphics, diagnostics.debug().orElseThrow(), x, y + 4, width);
        }
        pageContentHeight = Math.max(0, y + pageScroll - area.y() + 8);
        graphics.disableScissor();
    }

    private int renderDebugDiagnostics(
            GuiGraphicsExtractor graphics,
            DiagnosticsSettingsProjection.DebugSection section,
            int x,
            int y,
            int width) {
        SettingsDiagnosticsSnapshot.DebugSettingsDiagnostics debug = section.diagnostics();
        y = settingsHeading(
                graphics,
                Component.translatable(section.titleKey()),
                x,
                y,
                width,
                0xFFFFD479);
        y = debugLine(graphics, x, y, width,
                "screen.tomewisp.settings.diagnostics.debug.settings_generation",
                Long.toString(debug.settingsGeneration()));
        y = debugLine(graphics, x, y, width,
                "screen.tomewisp.settings.diagnostics.debug.database_schema",
                Integer.toString(debug.databaseSchema()));
        for (SettingsDiagnosticsSnapshot.DebugModelProfile model : debug.models()) {
            String value = model.profileId() + " · " + model.protocol()
                    + " · " + model.endpointAuthority() + " · " + model.modelId()
                    + " · context=" + model.effectiveContextWindowTokens()
                    + " · credential=" + model.credentialPresent();
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.model", value);
        }
        SettingsDiagnosticsSnapshot.DebugCapabilities capabilities = debug.capabilities();
        y = debugLine(graphics, x, y, width,
                "screen.tomewisp.settings.diagnostics.debug.capabilities",
                capabilities.catalogEntries() + "/" + capabilities.enabledEntries()
                        + " · sources=" + capabilities.knowledgeSources()
                        + " · tools=" + capabilities.tools()
                        + " · skills=" + capabilities.skills());
        if (debug.guide().isPresent()) {
            SettingsDiagnosticsSnapshot.DebugGuide guide = debug.guide().orElseThrow();
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.guide",
                    guide.scopeKind() + " · session=" + guide.selectedSessionId()
                            + " · " + guide.modelMode()
                            + " · persistence=" + guide.persistenceState()
                            + " · generations=" + guide.committedGeneration()
                            + "/" + guide.submittedGeneration()
                            + " · pending=" + guide.pendingWrites()
                            + " · active=" + guide.activeRequestCount());
            if (guide.request().isPresent()) {
                SettingsDiagnosticsSnapshot.DebugRequest request =
                        guide.request().orElseThrow();
                y = debugLine(graphics, x, y, width,
                        "screen.tomewisp.settings.diagnostics.debug.request",
                        request.requestId() + " · " + request.topology()
                                + " · " + request.status()
                                + " · retryMs=" + request.retryAfterMillis()
                                + " · tools=" + request.toolCount()
                                + " · sources=" + request.sourceCount());
            }
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.context",
                    "checkpoints=" + guide.context().checkpointCount()
                            + " · failed=" + guide.context().failedCheckpoints()
                            + " · estimatedTokens="
                            + guide.context().estimatedProjectionTokens());
            SettingsDiagnosticsSnapshot.DebugHistory history = guide.history();
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.history_window",
                    "loaded=" + history.loadedRequests() + "/" + history.totalRequests()
                            + " · cursorCounts=" + history.firstLoadedCount()
                            + ".." + history.lastLoadedCount()
                            + " · page=" + history.pageState());
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.presentation",
                    "cache=" + history.cacheHits() + "/" + history.cacheMisses()
                            + " · fallbacks=" + history.semanticFallbackCount());
        }
        for (SettingsDiagnosticsSnapshot.DebugSource source : debug.sources()) {
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.source",
                    source.sourceId() + " · " + source.state()
                            + " · generation=" + source.generation()
                            + " · count=" + source.itemCount()
                            + (source.failureCode() == null
                                    ? ""
                                    : " · failure=" + source.failureCode()));
        }
        for (String code : debug.failureCodes()) {
            y = debugLine(graphics, x, y, width,
                    "screen.tomewisp.settings.diagnostics.debug.failure", code);
        }
        return y;
    }

    private int settingsHeading(
            GuiGraphicsExtractor graphics,
            Component text,
            int x,
            int y,
            int width,
            int color) {
        for (net.minecraft.util.FormattedCharSequence line : font.split(text, width)) {
            graphics.text(font, line, x, y, color, false);
            y += 11;
        }
        return y + 4;
    }

    private int debugLine(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            String labelKey,
            String value) {
        Component line = Component.translatable(labelKey).copy().append(": ").append(value);
        for (net.minecraft.util.FormattedCharSequence wrapped : font.split(line, width - 8)) {
            graphics.text(font, wrapped, x + 4, y, MUTED, false);
            y += 10;
        }
        return y + 2;
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

    private void renderKnowledge(GuiGraphicsExtractor graphics) {
        SettingsLayout.Rect area = layout.editor();
        ToolSettingsProjection.Family family = selectedToolFamily();
        graphics.text(
                font,
                Component.translatable(family.titleKey()),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        graphics.text(
                font,
                Component.translatable(family.descriptionKey()),
                area.x() + 10,
                area.y() + 29,
                MUTED,
                false);
        selectedToolSource().ifPresent(source -> {
            int y = area.bottom() - (source.editable() ? 116 : 28);
            Component sourceInfo = Component.literal(source.displayName()).copy().append(" · ")
                    .append(Component.translatable(source.available()
                            ? "screen.tomewisp.settings.tools.source.ready"
                            : "screen.tomewisp.settings.tools.source.unavailable"));
            graphics.text(font, sourceInfo, area.x() + 10, y, MUTED, false);
            if (snapshot.display().debugMode()) {
                graphics.text(
                        font,
                        Component.literal(source.id() + " · " + source.kind()),
                        area.x() + 10,
                        y + 11,
                        MUTED,
                        false);
            }
        });
    }

    private void renderSkills(GuiGraphicsExtractor graphics) {
        SettingsLayout.Rect area = layout.editor();
        Optional<SkillSettingsProjection.Skill> selected = selectedSkill();
        graphics.text(
                font,
                Component.translatable("screen.tomewisp.settings.skills"),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        if (selected.isEmpty()) {
            graphics.text(
                    font,
                    Component.translatable("screen.tomewisp.settings.skills.empty"),
                    area.x() + 10,
                    area.y() + 31,
                    MUTED,
                    false);
            return;
        }
        SkillSettingsProjection.Skill skill = selected.orElseThrow();
        graphics.text(font, skill.name(), area.x() + 10, area.y() + 30, TEXT, false);
        graphics.text(
                font,
                Component.translatable(skill.localOverride()
                        ? "screen.tomewisp.settings.skills.local"
                        : "screen.tomewisp.settings.skills.bundled"),
                area.x() + 10,
                area.y() + 42,
                MUTED,
                false);
        if (skillEditing) {
            return;
        }
        int y = area.y() + 60;
        int width = Math.max(80, area.width() - 20);
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.literal(skill.description()), width)) {
            graphics.text(font, line, area.x() + 10, y, MUTED, false);
            y += 10;
        }
        y += 7;
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.literal(skill.body()), width)) {
            if (y > area.bottom() - 36) {
                break;
            }
            graphics.text(font, line, area.x() + 10, y, TEXT, false);
            y += 10;
        }
        if (snapshot.display().debugMode()) {
            graphics.text(
                    font,
                    Component.literal(skill.provenance()),
                    area.x() + 10,
                    Math.min(y + 6, area.bottom() - 38),
                    MUTED,
                    false);
        }
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

    private static boolean completedReload(
            ClientSettingsSnapshot previous,
            ClientSettingsSnapshot next,
            SettingsOperation.Kind reloadKind) {
        return previous.operation().kind() == reloadKind
                && next.operation().kind() == SettingsOperation.Kind.IDLE
                && next.notice() != null
                && next.notice().level() == SettingsNotice.Level.SUCCESS;
    }

    private List<Action> knowledgeActions() {
        return List.of(
                new Action(
                        "screen.tomewisp.settings.reload",
                        () -> accept(service.reloadToolSettings(selectedTool, true))),
                new Action("screen.tomewisp.settings.done", this::onClose));
    }

    private void saveCurrent() {
        confirmation = Confirmation.NONE;
        if (section == SettingsSection.MODELS) {
            save();
        }
    }

    private void reloadCurrent() {
        if (confirmation != Confirmation.RELOAD) {
            confirmation = Confirmation.RELOAD;
            localNotice = Component.translatable(
                    "screen.tomewisp.settings.confirm_reload").getString();
            rebuildWidgets();
            return;
        }
        confirmation = Confirmation.NONE;
        if (section == SettingsSection.MODELS) {
            accept(service.reloadModels(true));
        }
    }

    private void backOrClose() {
        if (!layout.wide() && section == SettingsSection.TOOLS && narrowToolDetail) {
            narrowToolDetail = false;
            selectedToolSourceId = null;
            rebuildWidgets();
            return;
        }
        if (!layout.wide() && section == SettingsSection.SKILLS && narrowSkillDetail) {
            narrowSkillDetail = false;
            skillEditing = false;
            skillDraftMarkdown = "";
            rebuildWidgets();
            return;
        }
        onClose();
    }

    private Component historyActionLabel(HistorySettingsProjection.ActionRow row) {
        if (historyConfirmation == null
                || historyConfirmation.action() != serviceHistoryAction(row.action())) {
            return Component.translatable(row.labelKey());
        }
        if (row.action() == HistorySettingsProjection.Action.RESET_DATABASE
                && historyConfirmation.stage()
                        == ClientSettingsService.ConfirmationStage.FIRST) {
            return Component.translatable(
                    "screen.tomewisp.settings.history.confirm_reset_again");
        }
        return Component.translatable("screen.tomewisp.settings.confirm");
    }

    private void activateHistory(HistorySettingsProjection.Action action) {
        ClientSettingsService.HistoryAction serviceAction = serviceHistoryAction(action);
        if (historyConfirmation == null
                || historyConfirmation.action() != serviceAction
                || historyConfirmation.generation() != snapshot.generation()) {
            ToolResult<ClientSettingsService.HistoryConfirmationToken> requested =
                    service.requestHistoryConfirmation(serviceAction);
            if (requested instanceof ToolResult.Success<
                    ClientSettingsService.HistoryConfirmationToken> success) {
                historyConfirmation = success.value();
                localNotice = Component.translatable(
                        action == HistorySettingsProjection.Action.RESET_DATABASE
                                ? "screen.tomewisp.settings.history.confirm_reset"
                                : "screen.tomewisp.settings.history.confirm_delete")
                        .getString();
                rebuildWidgets();
            } else {
                ToolResult.Failure<ClientSettingsService.HistoryConfirmationToken> failure =
                        (ToolResult.Failure<ClientSettingsService.HistoryConfirmationToken>) requested;
                localNotice = failure.message();
            }
            return;
        }
        if (action == HistorySettingsProjection.Action.RESET_DATABASE
                && historyConfirmation.stage()
                        == ClientSettingsService.ConfirmationStage.FIRST) {
            ToolResult<ClientSettingsService.HistoryConfirmationToken> second =
                    service.confirmHistoryReset(historyConfirmation);
            if (second instanceof ToolResult.Success<
                    ClientSettingsService.HistoryConfirmationToken> success) {
                historyConfirmation = success.value();
                localNotice = Component.translatable(
                        "screen.tomewisp.settings.history.confirm_reset_again_notice")
                        .getString();
                rebuildWidgets();
            } else {
                historyConfirmation = null;
                localNotice = ((ToolResult.Failure<
                        ClientSettingsService.HistoryConfirmationToken>) second).message();
            }
            return;
        }

        ClientSettingsService.HistoryConfirmationToken confirmed = historyConfirmation;
        historyConfirmation = null;
        confirmation = Confirmation.NONE;
        accept(switch (action) {
            case DELETE_CURRENT -> service.deleteCurrentHistory(confirmed);
            case DELETE_ACTOR -> service.deleteActorHistory(confirmed);
            case RESET_DATABASE -> service.resetHistoryDatabase(confirmed);
        });
    }

    private static ClientSettingsService.HistoryAction serviceHistoryAction(
            HistorySettingsProjection.Action action) {
        return switch (action) {
            case DELETE_CURRENT -> ClientSettingsService.HistoryAction.DELETE_CURRENT;
            case DELETE_ACTOR -> ClientSettingsService.HistoryAction.DELETE_ACTOR;
            case RESET_DATABASE -> ClientSettingsService.HistoryAction.RESET_DATABASE;
        };
    }

    private ToolSettingsProjection toolProjection() {
        return ToolSettingsProjection.from(snapshot.tools(), snapshot.display().debugMode());
    }

    private SkillSettingsProjection skillProjection() {
        return SkillSettingsProjection.from(snapshot.skills(), snapshot.display().debugMode());
    }

    private ToolSettingsProjection.Family selectedToolFamily() {
        return toolProjection().find(selectedTool).orElseGet(() -> {
            selectedTool = ToolFamilyId.RECIPES;
            return toolProjection().find(selectedTool).orElseThrow();
        });
    }

    private Optional<ToolSettingsProjection.Source> selectedToolSource() {
        if (selectedToolSourceId == null) {
            return Optional.empty();
        }
        return selectedToolFamily().sources().stream()
                .filter(source -> source.id().equals(selectedToolSourceId))
                .findFirst();
    }

    private Optional<SkillSettingsProjection.Skill> selectedSkill() {
        return selectedSkillName == null
                ? Optional.empty()
                : skillProjection().find(selectedSkillName);
    }

    private void addLocalMarkdownSource() {
        ToolSettingsProjection.Family guides =
                toolProjection().find(ToolFamilyId.GUIDES).orElseThrow();
        int suffix = 1;
        String shortName = "notes";
        while (containsSource(guides, "user:" + shortName)) {
            shortName = "notes-" + ++suffix;
        }
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        config.addProperty("directory", shortName);
        config.addProperty("locale", "zh_cn");
        List<ToolSourceDefinition> sources = new ArrayList<>();
        for (ToolSettingsProjection.Source source : guides.sources()) {
            sources.add(new ToolSourceDefinition(
                    source.id(),
                    source.kind(),
                    source.displayName(),
                    source.enabled(),
                    source.config(),
                    source.lifecycle()));
        }
        ToolSourceDefinition created = new ToolSourceDefinition(
                "user:" + shortName,
                "local_markdown",
                Component.translatable("screen.tomewisp.settings.tools.source.local_default")
                        .getString(),
                true,
                config,
                ToolSourceDefinition.Lifecycle.USER);
        sources.add(created);
        selectedToolSourceId = created.sourceId();
        accept(service.saveToolSettings(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION,
                ToolFamilyId.GUIDES,
                guides.enabled(),
                sources)));
    }

    private static boolean containsSource(
            ToolSettingsProjection.Family family, String sourceId) {
        return family.sources().stream().anyMatch(source -> source.id().equals(sourceId));
    }

    private void saveSelectedSource() {
        ToolSettingsProjection.Source selected = selectedToolSource().orElse(null);
        if (selected == null || !selected.editable()) {
            return;
        }
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        config.addProperty("directory", sourceDirectory.getValue().strip());
        config.addProperty("locale", sourceLocale.getValue().strip().toLowerCase(java.util.Locale.ROOT));
        List<ToolSourceDefinition> sources = new ArrayList<>();
        for (ToolSettingsProjection.Source source : selectedToolFamily().sources()) {
            sources.add(source.id().equals(selected.id())
                    ? new ToolSourceDefinition(
                            source.id(),
                            source.kind(),
                            sourceDisplayName.getValue().strip(),
                            source.enabled(),
                            config,
                            source.lifecycle())
                    : new ToolSourceDefinition(
                            source.id(),
                            source.kind(),
                            source.displayName(),
                            source.enabled(),
                            source.config(),
                            source.lifecycle()));
        }
        accept(service.saveToolSettings(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION,
                selectedTool,
                selectedToolFamily().enabled(),
                sources)));
    }

    private void deleteSelectedSource() {
        ToolSettingsProjection.Source selected = selectedToolSource().orElse(null);
        if (selected == null || !selected.deletable()) {
            return;
        }
        List<ToolSourceDefinition> sources = selectedToolFamily().sources().stream()
                .filter(source -> !source.id().equals(selected.id()))
                .map(source -> new ToolSourceDefinition(
                        source.id(),
                        source.kind(),
                        source.displayName(),
                        source.enabled(),
                        source.config(),
                        source.lifecycle()))
                .toList();
        selectedToolSourceId = null;
        accept(service.saveToolSettings(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION,
                selectedTool,
                selectedToolFamily().enabled(),
                sources)));
    }

    private void saveSkillOverride() {
        SkillSettingsProjection.Skill selected = selectedSkill().orElse(null);
        if (selected == null || skillDraftMarkdown.isBlank()) {
            return;
        }
        skillEditing = false;
        accept(service.saveSkillOverride(selected.name(), skillDraftMarkdown));
        skillDraftMarkdown = "";
    }

    private RecipeSettingsProjection recipeProjection() {
        return RecipeSettingsProjection.from(
                snapshot.recipes(), recipeDraft, snapshot.display().debugMode());
    }

    private Component preferredViewerLabel() {
        if (RecipeClientConfig.AUTO.equals(recipeDraft.preferredViewer())) {
            return Component.translatable("screen.tomewisp.settings.recipe.preferred_auto");
        }
        return recipeProjection().sources().stream()
                .filter(source -> source.actionId().equals(recipeDraft.preferredViewer()))
                .findFirst()
                .<Component>map(source -> friendlyTitle(
                        source.titleKey(), "screen.tomewisp.settings.recipe.source.other"))
                .orElseGet(() -> Component.translatable(
                        "screen.tomewisp.settings.recipe.preferred_unavailable"));
    }

    private static Component friendlyTitle(String titleKey, String fallbackKey) {
        return Component.translatable(Language.getInstance().has(titleKey) ? titleKey : fallbackKey);
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
        SecretValue replacement = pendingApiKey.isBlank()
                ? null
                : SecretValue.of(pendingApiKey);
        pendingApiKey = "";
        if (apiKey != null) {
            apiKey.setValue("");
        }
        accept(service.saveModels(candidate, definition.id(), replacement));
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
        accept(service.saveModels(new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, defaultId, retained)));
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
        SecretValue replacement = pendingApiKey.isBlank()
                ? null
                : SecretValue.of(pendingApiKey);
        service.testConnection(definition, replacement).thenAccept(result -> {
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
                localNotice = failure.code().equals("capability_dependency_conflict")
                        ? Component.translatable(
                                "screen.tomewisp.settings.capability.dependency_conflict")
                                .getString()
                        : failure.message();
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
        pageScroll = 0;
        pageContentHeight = 0;
        historyConfirmation = null;
        narrowToolDetail = false;
        narrowSkillDetail = false;
        skillEditing = false;
        skillDraftMarkdown = "";
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
        pendingApiKey = "";
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
        pendingApiKey = "";
        confirmation = Confirmation.NONE;
        localNotice = "";
        rebuildWidgets();
    }

    private boolean containsProfile(String profileId) {
        return snapshot.models().config().profiles().stream()
                .anyMatch(profile -> profile.id().equals(profileId));
    }

    private void captureDraft() {
        if (section != SettingsSection.MODELS || id == null) {
            return;
        }
        draft = new ModelProfileDraft(
                id.getValue(),
                displayName.getValue(),
                draftEnabled,
                draftProtocol,
                baseUrl.getValue(),
                model.getValue(),
                draft.credentialRef(),
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
                        profile.credentialPresent(),
                        profile.available(),
                        profile.definition().id().equals(
                                snapshot.models().config().defaultProfileId()),
                        profile.failure() == null ? null : profile.failure().code()))
                .toList();
        return new Projection(
                SettingsSection.topLevel(),
                cards,
                GeneralSettingsProjection.from(snapshot.display()),
                RecipeSettingsProjection.from(
                        snapshot.recipes(),
                        snapshot.recipes().config(),
                        snapshot.display().debugMode()),
                ToolSettingsProjection.from(
                        snapshot.tools(), snapshot.display().debugMode()),
                SkillSettingsProjection.from(
                        snapshot.skills(), snapshot.display().debugMode()),
                HistorySettingsProjection.from(
                        snapshot.history(),
                        snapshot.display().debugMode(),
                        snapshot.operation()),
                DiagnosticsSettingsProjection.from(snapshot.diagnostics()),
                snapshot.operation(),
                snapshot.notice());
    }

    record Projection(
            List<SettingsSection> sections,
            List<ModelCard> models,
            GeneralSettingsProjection general,
            RecipeSettingsProjection recipes,
            ToolSettingsProjection tools,
            SkillSettingsProjection skills,
            HistorySettingsProjection history,
            DiagnosticsSettingsProjection diagnostics,
            SettingsOperation operation,
            SettingsNotice notice) {
        Projection {
            sections = List.copyOf(sections);
            models = List.copyOf(models);
            Objects.requireNonNull(general, "general");
            Objects.requireNonNull(recipes, "recipes");
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(skills, "skills");
            Objects.requireNonNull(history, "history");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    record ModelCard(
            String id,
            String displayName,
            String model,
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

    private static final class PasswordEditBox extends EditBox {
        private PasswordEditBox(
                net.minecraft.client.gui.Font font,
                int x,
                int y,
                int width,
                int height,
                Component narration) {
            super(font, x, y, width, height, narration);
            addFormatter((text, offset) -> net.minecraft.util.FormattedCharSequence.forward(
                    "•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            if (event.isCopy() || event.isCut()) {
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            return Component.translatable("screen.tomewisp.settings.models.api_key");
        }
    }
}
