package dev.openallay.client.gui;

import dev.openallay.client.gui.settings.DiagnosticsSettingsProjection;
import dev.openallay.client.gui.settings.GeneralSettingsProjection;
import dev.openallay.client.gui.settings.HistorySettingsProjection;
import dev.openallay.client.gui.settings.ModelProfileDraft;
import dev.openallay.client.gui.settings.RecipeSettingsProjection;
import dev.openallay.client.gui.settings.SettingsLayout;
import dev.openallay.client.gui.settings.SettingsSection;
import dev.openallay.client.gui.settings.ToolSettingsProjection;
import dev.openallay.client.gui.settings.SkillSettingsProjection;
import dev.openallay.guide.e2e.GuideClientE2EConfig;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.model.config.ModelProfilesConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import dev.openallay.model.catalog.ModelCatalog;
import dev.openallay.model.catalog.ModelCatalogRequest;
import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.settings.ClientSettingsService;
import dev.openallay.settings.ClientSettingsSnapshot;
import dev.openallay.settings.SettingsNotice;
import dev.openallay.settings.SettingsOperation;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot;
import dev.openallay.settings.model.ModelConnectionResult;
import dev.openallay.settings.model.ModelProfileSettingsView;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.config.ToolFamilyConfig;
import dev.openallay.tool.config.ToolFamilyId;
import dev.openallay.tool.config.ToolSourceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Native settings shell and model-profile editor backed only by ClientSettingsService. */
public final class OpenAllaySettingsScreen extends Screen {
    private static final int BACKGROUND = 0xE00B0D12;
    private static final int PANEL = 0xE0181B22;
    private static final int PANEL_ALT = 0xE0242933;
    private static final int ACCENT = 0xFF72D5C4;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFFA9B3BE;
    private static final int ERROR = 0xFFFF7D7D;
    private static final String REPOSITORY_URL = "https://github.com/nkanf-dev/OpenAllay";
    private static final Identifier ABOUT_BANNER = Identifier.fromNamespaceAndPath(
            "openallay", "textures/gui/about_banner.png");

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
    private SourceDraft sourceDraft;
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
    private EditBox assistantName;
    private String assistantNameDraft;
    private List<String> catalogModelIds = List.of();
    private boolean modelCatalogOpen;
    private int modelCatalogPage;
    private long modelCatalogGeneration;

    public OpenAllaySettingsScreen(
            ClientSettingsService service,
            Runnable returnToGuide) {
        super(Component.translatable("screen.openallay.settings.title"));
        this.service = Objects.requireNonNull(service, "service");
        this.returnToGuide = Objects.requireNonNull(returnToGuide, "returnToGuide");
        this.snapshot = service.snapshot();
        assistantNameDraft = snapshot.display().assistantName();
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
        } else if (section == SettingsSection.ABOUT) {
            addAboutPage();
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
            if (!previous.display().equals(next.display())
                    || completedReload(
                            previous, next, SettingsOperation.Kind.RELOADING_DISPLAY)) {
                assistantNameDraft = next.display().assistantName();
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
        service.cancelModelCatalog();
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
            if (modelCatalogOpen) {
                int pageSize = modelCatalogPageSize();
                int pages = Math.max(1, (catalogModelIds.size() + pageSize - 1) / pageSize);
                modelCatalogPage = net.minecraft.util.Mth.clamp(
                        modelCatalogPage - (int) Math.signum(scrollY), 0, pages - 1);
                rebuildWidgets();
                return true;
            }
            captureDraft();
            int viewport = Math.max(1, layout.editor().height() - 38);
            int maximum = Math.max(0, 206 - viewport);
            editorScroll = net.minecraft.util.Mth.clamp(
                    editorScroll - (int) Math.round(scrollY * 22), 0, maximum);
            rebuildWidgets();
            return true;
        }
        boolean scrollablePage = (section == SettingsSection.DIAGNOSTICS
                        || section == SettingsSection.HISTORY)
                && layout.content().contains(mouseX, mouseY);
        boolean scrollableTools = section == SettingsSection.TOOLS
                && layout.editor().contains(mouseX, mouseY);
        if (scrollablePage || scrollableTools) {
            int maximum = Math.max(0, pageContentHeight - layout.content().height() + 18);
            int replacement = net.minecraft.util.Mth.clamp(
                    pageScroll - (int) Math.round(scrollY * 24), 0, maximum);
            if (replacement != pageScroll) {
                if (scrollableTools) captureSourceDraft();
                pageScroll = replacement;
                if (scrollableTools) rebuildWidgets();
            }
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
        } else if (section == SettingsSection.ABOUT) {
            renderAbout(graphics);
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
            addRenderableWidget(OpenAllayButton.create(
                            Component.translatable("screen.openallay.settings.back"),
                            ignored -> backOrClose())
                    .bounds(backX, y, 56, 20)
                    .build());
            int sectionX = layout.header().x() + 90;
            addRenderableWidget(OpenAllayButton.create(
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
            Button button = addRenderableWidget(OpenAllayButton.create(
                            Component.translatable(candidate.translationKey()),
                            ignored -> switchSection(candidate))
                    .selected(candidate == section)
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
        if (modelCatalogOpen) {
            addModelCatalogPicker();
        } else {
            addEditor();
        }
    }

    private void addGeneralPage() {
        GeneralSettingsProjection general = project(snapshot).general();
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 10;
        int y = area.y() + 44;
        int width = Math.min(280, Math.max(120, area.width() - 20));
        int saveWidth = Math.min(72, Math.max(50, width / 4));
        assistantName = new EditBox(
                font,
                x,
                y,
                Math.max(60, width - saveWidth - 4),
                20,
                Component.translatable(general.assistantNameLabelKey()));
        assistantName.setValue(assistantNameDraft);
        assistantName.setMaxLength(Integer.MAX_VALUE);
        assistantName.setResponder(value -> assistantNameDraft = value);
        addRenderableWidget(assistantName);
        Button saveName = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable(
                                "screen.openallay.settings.general.assistant_name.save"),
                        ignored -> saveAssistantName(general))
                .bounds(x + width - saveWidth, y, saveWidth, 20)
                .build());
        saveName.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        Button debug = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable(
                                general.debugLabelKey()).copy().append(" · ")
                                .append(Component.translatable(general.debugStatusKey())),
                        ignored -> accept(service.saveDisplay(general.toggleDebug())))
                .bounds(x, y + 34, width, 22)
                .build());
        debug.setTooltip(Tooltip.create(
                Component.translatable(general.debugDescriptionKey())));
        debug.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        Button animations = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable(
                                general.animationsLabelKey()).copy().append(" · ")
                                .append(Component.translatable(general.animationsStatusKey())),
                        ignored -> accept(service.saveDisplay(general.toggleAnimations())))
                .bounds(x, y + 64, width, 22)
                .build());
        animations.setTooltip(Tooltip.create(
                Component.translatable(general.animationsDescriptionKey())));
        animations.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
    }

    private void addAboutPage() {
        SettingsLayout.Rect area = layout.editor();
        int width = Math.min(240, Math.max(120, area.width() - 20));
        addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.about.copy_repository"),
                        ignored -> copyRepositoryUrl())
                .bounds(area.x() + 10, area.bottom() - 30, width, 20)
                .build());
    }

    private void saveAssistantName(GeneralSettingsProjection general) {
        try {
            accept(service.saveDisplay(general.renameAssistant(assistantNameDraft)));
        } catch (IllegalArgumentException failure) {
            localNotice = Component.translatable(
                    "screen.openallay.settings.general.assistant_name.invalid").getString();
        }
    }

    private void copyRepositoryUrl() {
        try {
            minecraft.keyboardHandler.setClipboard(REPOSITORY_URL);
            localNotice = Component.translatable(
                    "screen.openallay.settings.about.copy_success").getString();
        } catch (RuntimeException failure) {
            localNotice = Component.translatable(
                    "screen.openallay.settings.about.copy_failed").getString();
        }
    }

    private void addHistoryPage() {
        HistorySettingsProjection history = project(snapshot).history();
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 10;
        int y = area.y() + 64 - pageScroll;
        int width = Math.min(360, Math.max(140, area.width() - 20));
        for (HistorySettingsProjection.ActionRow row : history.actions()) {
            Button button = OpenAllayButton.create(
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
            Button button = addRenderableWidget(OpenAllayButton.create(
                            label,
                            ignored -> selectAndRebuild(card.id()))
                    .selected(card.id().equals(selectedProfileId))
                    .bounds(x, y, buttonWidth, 22)
                    .build());
            button.active = !card.id().equals(selectedProfileId);
            y += 26;
            if (y > layout.list().bottom() - 50) {
                break;
            }
        }
        addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.add"),
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
            Button button = addRenderableWidget(OpenAllayButton.create(
                            Component.translatable(family.titleKey()),
                            ignored -> {
                                captureSourceDraft();
                                selectedTool = family.id();
                                selectedToolSourceId = null;
                                sourceDraft = null;
                                pageScroll = 0;
                                narrowToolDetail = true;
                                rebuildWidgets();
                            })
                    .selected(family.id() == selectedTool)
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
        int y = area.y() + 48 - pageScroll;
        int width = area.width() - 18;
        Button enable = OpenAllayButton.create(
                        Component.translatable(family.enabled()
                                ? "screen.openallay.settings.tools.disable"
                                : "screen.openallay.settings.tools.enable"),
                        ignored -> accept(service.saveToolSettings(
                                toolProjection().toggleTool(family.id()))))
                .bounds(x, y, Math.max(80, (width - 4) / 2), 22)
                .build();
        enable.active = family.available()
                && snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        addToolWidget(enable, area);
        int enableWidth = enable.getWidth();
        Button restore = OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.tools.restore"),
                        ignored -> accept(service.restoreToolSettings(family.id())))
                .bounds(x + enableWidth + 4, y, width - enableWidth - 4, 22)
                .build();
        restore.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        addToolWidget(restore, area);
        y += 32;

        if (family.id() == ToolFamilyId.RECIPES) {
            int half = Math.max(80, (width - 4) / 2);
            Button visibility = OpenAllayButton.create(
                            Component.translatable(
                                    recipeDraft.visibility() == RecipeVisibilityPolicy.ALL_KNOWN
                                            ? "screen.openallay.settings.recipe.visibility_all"
                                            : "screen.openallay.settings.recipe.visibility_unlocked"),
                            ignored -> {
                                recipeDraft = recipeProjection().cycleVisibility();
                                accept(service.saveRecipeSettings(recipeDraft));
                            })
                    .bounds(x, y, half, 20)
                    .build();
            addToolWidget(visibility, area);
            Button preferred = OpenAllayButton.create(
                            Component.translatable(
                                    "screen.openallay.settings.recipe.preferred",
                                    preferredViewerLabel()),
                            ignored -> {
                                recipeDraft = recipeProjection().cyclePreferredViewer();
                                accept(service.saveRecipeSettings(recipeDraft));
                            })
                    .bounds(x + half + 4, y, width - half - 4, 20)
                    .build();
            addToolWidget(preferred, area);
            y += 30;
        }

        y += toolCardsHeight(family, width) + 8;

        for (ToolSettingsProjection.Source source : family.sources()) {
            int toggleWidth = Math.min(76, Math.max(58, width / 4));
            Button select = OpenAllayButton.create(
                            Component.literal(source.displayName()),
                            ignored -> {
                                captureSourceDraft();
                                selectedToolSourceId = source.id();
                                sourceDraft = null;
                                rebuildWidgets();
                            })
                    .selected(source.id().equals(selectedToolSourceId))
                    .bounds(x, y, width - toggleWidth - 4, 20)
                    .build();
            select.active = !source.id().equals(selectedToolSourceId);
            addToolWidget(select, area);
            Button toggle = OpenAllayButton.create(
                            Component.translatable(source.enabled()
                                    ? "screen.openallay.settings.capability.enabled"
                                    : "screen.openallay.settings.capability.disabled"),
                            ignored -> accept(service.saveToolSettings(
                                    toolProjection().toggleSource(family.id(), source.id()))))
                    .bounds(x + width - toggleWidth, y, toggleWidth, 20)
                    .build();
            toggle.active = source.available()
                    && snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
            addToolWidget(toggle, area);
            y += 24;
        }

        if (family.id() == ToolFamilyId.GUIDES) {
            Button add = OpenAllayButton.create(
                            Component.translatable("screen.openallay.settings.tools.source.add_local"),
                            ignored -> addLocalMarkdownSource())
                    .bounds(x, y + 2, Math.min(180, width), 20)
                    .build();
            addToolWidget(add, area);
            y += 30;
        }
        int sourceEditorY = y;
        selectedToolSource().ifPresent(
                source -> addSourceEditor(source, x, sourceEditorY, width));
        pageContentHeight = Math.max(
                0, y + pageScroll - area.y() + (selectedToolSource().isPresent() ? 116 : 12));
    }

    private void addToolWidget(Button widget, SettingsLayout.Rect area) {
        if (widget.getY() >= area.y() + 42 && widget.getY() + widget.getHeight() <= area.bottom()) {
            addRenderableWidget(widget);
        }
    }

    private void addSourceEditor(
            ToolSettingsProjection.Source source, int x, int y, int width) {
        if (!source.editable()) {
            return;
        }
        SourceDraft retained = sourceDraft != null && sourceDraft.sourceId().equals(source.id())
                ? sourceDraft
                : new SourceDraft(
                        source.id(),
                        source.displayName(),
                        source.config().get("directory").getAsString(),
                        source.config().get("locale").getAsString());
        sourceDisplayName = field(
                x, y, width, "screen.openallay.settings.tools.source.name", retained.displayName());
        y += 22;
        sourceDirectory = field(
                x,
                y,
                width,
                "screen.openallay.settings.tools.source.directory",
                retained.directory());
        y += 22;
        sourceLocale = field(
                x,
                y,
                width,
                "screen.openallay.settings.tools.source.locale",
                retained.locale());
        y += 24;
        int half = Math.max(60, (width - 4) / 2);
        Button save = OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.save"),
                        ignored -> saveSelectedSource())
                .bounds(x, y, half, 20)
                .build();
        addToolWidget(save, layout.editor());
        Button delete = OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.delete"),
                        ignored -> deleteSelectedSource())
                .bounds(x + half + 4, y, width - half - 4, 20)
                .build();
        addToolWidget(delete, layout.editor());
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
                            ? "screen.openallay.settings.skills.local"
                            : "screen.openallay.settings.skills.bundled"));
            Button button = addRenderableWidget(OpenAllayButton.create(label, ignored -> {
                        selectedSkillName = skill.name();
                        narrowSkillDetail = true;
                        skillEditing = false;
                        skillDraftMarkdown = "";
                        rebuildWidgets();
                    })
                    .selected(skill.name().equals(selectedSkillName))
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
                                "screen.openallay.settings.skills.editor_placeholder"))
                        .build(
                                font,
                                editorWidth,
                                Math.max(70, area.bottom() - editorY - 34),
                                Component.translatable("screen.openallay.settings.skills.editor"));
                skillEditor.setValue(skillDraftMarkdown, true);
                skillEditor.setValueListener(value -> skillDraftMarkdown = value);
                addRenderableWidget(skillEditor);
                addRenderableWidget(OpenAllayButton.create(
                                Component.translatable("screen.openallay.settings.save"),
                                ignored -> saveSkillOverride())
                        .bounds(editorX, area.bottom() - 26, Math.min(120, editorWidth), 20)
                        .build());
                addRenderableWidget(OpenAllayButton.create(
                                Component.translatable("screen.openallay.settings.cancel"),
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
                addRenderableWidget(OpenAllayButton.create(
                                Component.translatable(skill.createsOverrideOnSave()
                                        ? "screen.openallay.settings.skills.create_override"
                                        : "screen.openallay.settings.skills.edit_override"),
                                ignored -> {
                                    skillEditing = true;
                                    skillDraftMarkdown = skill.markdown();
                                    rebuildWidgets();
                                })
                        .bounds(editorX, area.bottom() - 26, Math.min(160, editorWidth), 20)
                        .build());
                if (skill.canDeleteOverride()) {
                    addRenderableWidget(OpenAllayButton.create(
                                    Component.translatable(
                                            "screen.openallay.settings.skills.delete_override"),
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
        addRenderableWidget(OpenAllayButton.create(protocolLabel(), ignored -> cycleProtocol())
                .bounds(x, y, toggleWidth, 20).build());
        addRenderableWidget(OpenAllayButton.create(enabledLabel(), ignored -> toggleEnabled())
                .bounds(x + toggleWidth + 6, y, toggleWidth, 20).build());
        y += 32 - editorScroll;
        int labelWidth = Math.min(104, Math.max(72, editorWidth / 3));
        int inputX = x + labelWidth;
        int inputWidth = Math.max(70, editorWidth - labelWidth);
        id = field(inputX, y, inputWidth, "screen.openallay.settings.models.id", draft.id());
        y += 22;
        displayName = field(
                inputX, y, inputWidth, "screen.openallay.settings.models.name", draft.displayName());
        y += 22;
        baseUrl = field(
                inputX, y, inputWidth, "screen.openallay.settings.models.base_url", draft.baseUrl());
        baseUrl.setResponder(value -> {
            confirmation = Confirmation.NONE;
            invalidateModelCatalog();
        });
        y += 22;
        int fetchWidth = inputWidth >= 130 ? 46 : 30;
        int chooseWidth = inputWidth >= 130 ? 32 : 20;
        int modelWidth = Math.max(20, inputWidth - fetchWidth - chooseWidth - 6);
        model = field(
                inputX, y, modelWidth, "screen.openallay.settings.models.model_id", draft.model());
        Button fetch = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.fetch"),
                        ignored -> fetchModelCatalog())
                .bounds(inputX + modelWidth + 3, y, fetchWidth, 18)
                .build());
        fetch.active = snapshot.operation().kind() == SettingsOperation.Kind.IDLE;
        fetch.visible = model.visible;
        Button choose = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.choose"),
                        ignored -> {
                            captureDraft();
                            modelCatalogOpen = true;
                            modelCatalogPage = 0;
                            rebuildWidgets();
                        })
                .bounds(inputX + modelWidth + fetchWidth + 6, y, chooseWidth, 18)
                .build());
        choose.active = !catalogModelIds.isEmpty();
        choose.visible = model.visible;
        y += 22;
        apiKey = passwordField(inputX, y, inputWidth);
        y += 22;
        contextWindow = field(
                inputX,
                y,
                inputWidth,
                "screen.openallay.settings.models.context_window",
                draft.contextWindowTokens());
        y += 22;
        maxOutput = field(
                inputX, y, inputWidth, "screen.openallay.settings.models.max_output", draft.maxOutputTokens());
        y += 22;
        connectTimeout = field(
                inputX,
                y,
                inputWidth,
                "screen.openallay.settings.models.connect_timeout",
                draft.connectTimeoutSeconds());
        y += 22;
        requestTimeout = field(
                inputX,
                y,
                inputWidth,
                "screen.openallay.settings.models.request_timeout",
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
                Component.translatable("screen.openallay.settings.models.api_key"));
        field.setValue(pendingApiKey);
        boolean saved = selectedView().map(
                        ModelProfileSettingsView.Profile::credentialStoredLocally)
                .orElse(false);
        boolean environment = selectedView().map(
                        ModelProfileSettingsView.Profile::credentialFromEnvironment)
                .orElse(false);
        field.setHint(Component.translatable(saved
                ? "screen.openallay.settings.models.api_key_saved_hint"
                : environment
                        ? "screen.openallay.settings.models.api_key_environment_hint"
                        : "screen.openallay.settings.models.api_key_enter_hint"));
        field.setMaxLength(4096);
        field.setResponder(value -> {
            pendingApiKey = value;
            confirmation = Confirmation.NONE;
            invalidateModelCatalog();
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
            Button button = addRenderableWidget(OpenAllayButton.create(
                            Component.translatable(action.translationKey()),
                            ignored -> action.action().run())
                    .bounds(x, y, buttonWidth, 20)
                    .build());
            button.active = actionEnabled(action.translationKey());
        }
    }

    private List<Action> footerActions() {
        return switch (section) {
            case MODELS -> modelCatalogOpen ? List.of(
                    new Action("screen.openallay.settings.models.catalog_close", () -> {
                        modelCatalogOpen = false;
                        rebuildWidgets();
                    }),
                    new Action("screen.openallay.settings.done", this::onClose)) : List.of(
                    new Action("screen.openallay.settings.save", this::saveCurrent),
                    new Action(reloadKey(), this::reloadCurrent),
                    new Action(deleteKey(), this::delete),
                    new Action("screen.openallay.settings.models.default", this::makeDefault),
                    new Action(testKey(), this::testConnection),
                    new Action("screen.openallay.settings.cancel", this::cancel),
                    new Action("screen.openallay.settings.models.refresh", this::refreshMetadata),
                    new Action("screen.openallay.settings.done", this::onClose));
            case TOOLS -> knowledgeActions();
            case SKILLS -> List.of(
                    new Action(
                            "screen.openallay.settings.reload",
                            () -> accept(service.reloadSkills(true))),
                    new Action("screen.openallay.settings.done", this::onClose));
            case GENERAL -> List.of(
                    new Action(
                            "screen.openallay.settings.reload",
                            () -> accept(service.reloadDisplay())),
                    new Action("screen.openallay.settings.done", this::onClose));
            case HISTORY, DIAGNOSTICS, ABOUT ->
                    List.of(new Action("screen.openallay.settings.done", this::onClose));
        };
    }

    private boolean actionEnabled(String key) {
        boolean busy = snapshot.operation().kind() != SettingsOperation.Kind.IDLE;
        if (key.equals("screen.openallay.settings.cancel")) {
            return snapshot.operation().kind() == SettingsOperation.Kind.TESTING_CONNECTION
                    || snapshot.operation().kind()
                            == SettingsOperation.Kind.FETCHING_MODEL_CATALOG;
        }
        if (key.equals("screen.openallay.settings.done")) {
            return true;
        }
        return !busy;
    }

    private void renderModels(GuiGraphicsExtractor graphics) {
        if (layout.wide()) {
            graphics.text(
                    font,
                    Component.translatable("screen.openallay.settings.models.profiles"),
                    layout.list().x() + 8,
                    layout.list().y() + 9,
                    ACCENT,
                    false);
        }
        SettingsLayout.Rect area = layout.editor();
        if (modelCatalogOpen) {
            graphics.text(
                    font,
                    Component.translatable(
                            "screen.openallay.settings.models.catalog_title",
                            catalogModelIds.size()),
                    area.x() + 8,
                    area.y() + 10,
                    ACCENT,
                    false);
            if (catalogModelIds.isEmpty()) {
                graphics.text(font,
                        Component.translatable("screen.openallay.settings.models.catalog_empty"),
                        area.x() + 8, area.y() + 34, MUTED, false);
            }
            return;
        }
        int x = area.x() + 8;
        int y = area.y() + 43 - editorScroll;
        String[] labels = {
            "screen.openallay.settings.models.id",
            "screen.openallay.settings.models.name",
            "screen.openallay.settings.models.base_url",
            "screen.openallay.settings.models.model_id",
            "screen.openallay.settings.models.api_key",
            "screen.openallay.settings.models.context_window",
            "screen.openallay.settings.models.max_output",
            "screen.openallay.settings.models.connect_timeout",
            "screen.openallay.settings.models.request_timeout"
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
                                    ? "screen.openallay.settings.models.available"
                                    : "screen.openallay.settings.models.unavailable")
                    .copy().append(" · ")
                    .append(Component.translatable(pendingApiKey.isBlank()
                            ? (profile.credentialStoredLocally()
                                    ? "screen.openallay.settings.models.api_key_saved"
                                    : profile.credentialFromEnvironment()
                                            ? "screen.openallay.settings.models.api_key_environment"
                                            : "screen.openallay.settings.models.api_key_not_set")
                            : "screen.openallay.settings.models.api_key_replace"));
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
        graphics.text(
                font,
                Component.translatable(general.assistantNameLabelKey()),
                area.x() + 10,
                area.y() + 31,
                MUTED,
                false);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.translatable(general.assistantNameDescriptionKey()),
                Math.max(80, area.width() - 20));
        int y = area.y() + 136;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.text(font, line, area.x() + 10, y, MUTED, false);
            y += 10;
        }
        y += 5;
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.translatable(general.debugDescriptionKey()),
                Math.max(80, area.width() - 20))) {
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

    private void renderAbout(GuiGraphicsExtractor graphics) {
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 10;
        int contentWidth = Math.max(100, area.width() - 20);
        graphics.text(
                font,
                Component.translatable("screen.openallay.settings.about.title"),
                x,
                area.y() + 12,
                ACCENT,
                false);
        int bannerWidth = Math.min(contentWidth, 512);
        int bannerHeight = Math.max(54, bannerWidth * 9 / 16);
        int bannerX = x + Math.max(0, (contentWidth - bannerWidth) / 2);
        int bannerY = area.y() + 31;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ABOUT_BANNER,
                bannerX,
                bannerY,
                0.0F,
                0.0F,
                bannerWidth,
                bannerHeight,
                1024,
                576,
                1024,
                576);
        int y = bannerY + bannerHeight + 12;
        for (net.minecraft.util.FormattedCharSequence line : font.split(
                Component.translatable("screen.openallay.settings.about.description"),
                contentWidth)) {
            graphics.text(font, line, x, y, TEXT, false);
            y += 10;
        }
        y += 8;
        graphics.text(
                font,
                Component.translatable("screen.openallay.settings.about.repository"),
                x,
                y,
                MUTED,
                false);
        graphics.text(font, REPOSITORY_URL, x, y + 12, ACCENT, false);
        pageContentHeight = y + 24 - area.y();
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
                "screen.openallay.settings.diagnostics.debug.settings_generation",
                Long.toString(debug.settingsGeneration()));
        y = debugLine(graphics, x, y, width,
                "screen.openallay.settings.diagnostics.debug.database_schema",
                Integer.toString(debug.databaseSchema()));
        for (SettingsDiagnosticsSnapshot.DebugModelProfile model : debug.models()) {
            String value = model.profileId() + " · " + model.protocol()
                    + " · " + model.endpointAuthority() + " · " + model.modelId()
                    + " · context=" + model.effectiveContextWindowTokens()
                    + " · credential=" + model.credentialPresent();
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.model", value);
        }
        SettingsDiagnosticsSnapshot.DebugCapabilities capabilities = debug.capabilities();
        y = debugLine(graphics, x, y, width,
                "screen.openallay.settings.diagnostics.debug.capabilities",
                capabilities.catalogEntries() + "/" + capabilities.enabledEntries()
                        + " · sources=" + capabilities.knowledgeSources()
                        + " · tools=" + capabilities.tools()
                        + " · skills=" + capabilities.skills());
        if (debug.guide().isPresent()) {
            SettingsDiagnosticsSnapshot.DebugGuide guide = debug.guide().orElseThrow();
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.guide",
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
                        "screen.openallay.settings.diagnostics.debug.request",
                        request.requestId() + " · " + request.topology()
                                + " · " + request.status()
                                + " · retryMs=" + request.retryAfterMillis()
                                + " · tools=" + request.toolCount()
                                + " · sources=" + request.sourceCount());
            }
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.context",
                    "checkpoints=" + guide.context().checkpointCount()
                            + " · failed=" + guide.context().failedCheckpoints()
                            + " · estimatedTokens="
                            + guide.context().estimatedProjectionTokens());
            SettingsDiagnosticsSnapshot.DebugHistory history = guide.history();
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.history_window",
                    "loaded=" + history.loadedRequests() + "/" + history.totalRequests()
                            + " · cursorCounts=" + history.firstLoadedCount()
                            + ".." + history.lastLoadedCount()
                            + " · page=" + history.pageState());
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.presentation",
                    "cache=" + history.cacheHits() + "/" + history.cacheMisses()
                            + " · fallbacks=" + history.semanticFallbackCount());
        }
        for (SettingsDiagnosticsSnapshot.DebugSource source : debug.sources()) {
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.source",
                    source.sourceId() + " · " + source.state()
                            + " · generation=" + source.generation()
                            + " · count=" + source.itemCount()
                            + (source.failureCode() == null
                                    ? ""
                                    : " · failure=" + source.failureCode()));
        }
        for (String code : debug.failureCodes()) {
            y = debugLine(graphics, x, y, width,
                    "screen.openallay.settings.diagnostics.debug.failure", code);
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
                Component.translatable("screen.openallay.settings.section_pending"),
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
        graphics.enableScissor(area.x(), area.y() + 42, area.right(), area.bottom());
        int cardsY = area.y() + (family.id() == ToolFamilyId.RECIPES ? 110 : 80) - pageScroll;
        int cardsX = area.x() + 9;
        int cardsWidth = Math.max(80, area.width() - 18);
        for (ToolSettingsProjection.ToolCard card : family.tools()) {
            cardsY = renderToolCard(graphics, card, cardsX, cardsY, cardsWidth) + 6;
        }
        int sourcesY = cardsY;
        selectedToolSource().ifPresent(source -> {
            int y = sourcesY + family.sources().size() * 24
                    + (source.editable() ? 108 : 8);
            Component sourceInfo = Component.literal(source.displayName()).copy().append(" · ")
                    .append(Component.translatable(source.available()
                            ? "screen.openallay.settings.tools.source.ready"
                            : "screen.openallay.settings.tools.source.unavailable"));
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
        graphics.disableScissor();
    }

    private int renderToolCard(
            GuiGraphicsExtractor graphics,
            ToolSettingsProjection.ToolCard card,
            int x,
            int y,
            int width) {
        int height = toolCardHeight(card, width);
        graphics.fill(x, y, x + width, y + height, PANEL_ALT);
        graphics.outline(x, y, width, height, card.available() ? 0xFF46515F : 0xFF68484D);
        int cursor = y + 7;
        String status = card.available()
                ? card.enabled() ? "✓ " : "○ "
                : "! ";
        graphics.text(
                font,
                Component.literal(status).append(Component.translatable(card.titleKey())),
                x + 7,
                cursor,
                card.available() ? TEXT : ERROR,
                false);
        cursor += 13;
        cursor = renderWrapped(
                graphics,
                Component.translatable(card.descriptionKey()),
                x + 7,
                cursor,
                width - 14,
                MUTED,
                10);
        Component badges = Component.translatable(card.readOnly()
                        ? "screen.openallay.settings.tools.read_only"
                        : "screen.openallay.settings.tools.restricted")
                .copy().append(" · ")
                .append(Component.translatable(card.available()
                        ? card.enabled()
                                ? "screen.openallay.settings.tools.available_enabled"
                                : "screen.openallay.settings.tools.available_disabled"
                        : "screen.openallay.settings.tools.unavailable"));
        graphics.text(font, badges, x + 7, cursor + 2, ACCENT, false);
        cursor += 16;
        cursor = renderToolFields(
                graphics,
                Component.translatable("screen.openallay.settings.tools.inputs"),
                card.parameters().stream().map(parameter -> new ToolField(
                        parameter.name(), parameter.label(), parameter.type(),
                        parameter.required(), parameter.description())).toList(),
                x + 7,
                cursor,
                width - 14,
                true);
        cursor = renderToolFields(
                graphics,
                Component.translatable("screen.openallay.settings.tools.returns"),
                card.returns().stream().map(value -> new ToolField(
                        value.name(), value.label(), value.type(), false, value.description())).toList(),
                x + 7,
                cursor,
                width - 14,
                false);
        if (card.debug().isPresent()) {
            cursor += 3;
            graphics.text(
                    font,
                    Component.translatable("screen.openallay.settings.tools.debug"),
                    x + 7,
                    cursor,
                    0xFFFFD479,
                    false);
            cursor += 13;
            ToolSettingsProjection.Debug debug = card.debug().orElseThrow();
            cursor = renderWrapped(graphics, Component.literal(
                            "ID: " + debug.toolId() + " · alias: " + debug.modelAlias()),
                    x + 9, cursor, width - 18, MUTED, 10);
            cursor = renderWrapped(graphics, Component.literal(
                            "provider: " + debug.providerId() + " · access: " + debug.access()),
                    x + 9, cursor, width - 18, MUTED, 10);
            cursor = renderWrapped(graphics, Component.translatable(debug.executionKey()).copy()
                            .append(" · context: ")
                            .append(debug.requiredContext().isEmpty()
                                    ? "none" : String.join(", ", debug.requiredContext())),
                    x + 9, cursor, width - 18, MUTED, 10);
            if (debug.diagnostic() != null) {
                cursor = renderWrapped(graphics, Component.literal(
                                "diagnostic: " + debug.diagnostic()),
                        x + 9, cursor, width - 18, ERROR, 10);
            }
            cursor = renderDebugSchema(
                    graphics, "input schema", debug.inputSchema(), x + 9, cursor, width - 18);
            cursor = renderDebugSchema(
                    graphics, "output schema", debug.outputSchema(), x + 9, cursor, width - 18);
        }
        return y + height;
    }

    private int renderToolFields(
            GuiGraphicsExtractor graphics,
            Component heading,
            List<ToolField> fields,
            int x,
            int y,
            int width,
            boolean showRequired) {
        graphics.text(font, heading, x, y, ACCENT, false);
        y += 12;
        if (fields.isEmpty()) {
            graphics.text(
                    font,
                    Component.translatable("screen.openallay.settings.tools.none"),
                    x + 5,
                    y,
                    MUTED,
                    false);
            return y + 12;
        }
        for (ToolField field : fields) {
            Component label = toolFieldLabel(field.name(), field.fallbackLabel()).copy()
                    .append(" · " + field.type());
            if (showRequired) {
                label = label.copy().append(" · ").append(Component.translatable(field.required()
                        ? "screen.openallay.settings.tools.required"
                        : "screen.openallay.settings.tools.optional"));
            }
            y = renderWrapped(graphics, label, x + 5, y, width - 5, TEXT, 10);
            if (hasToolFieldDescription(field)) {
                y = renderWrapped(
                        graphics,
                        toolFieldDescription(field.name(), field.description()),
                        x + 11,
                        y,
                        width - 11,
                        MUTED,
                        10);
            }
        }
        return y + 2;
    }

    private int renderDebugSchema(
            GuiGraphicsExtractor graphics,
            String heading,
            String schema,
            int x,
            int y,
            int width) {
        graphics.text(font, heading, x, y, 0xFFFFD479, false);
        y += 11;
        if (schema.isBlank()) {
            graphics.text(font, "unavailable", x + 4, y, MUTED, false);
            return y + 11;
        }
        for (String sourceLine : schema.split("\\R", -1)) {
            y = renderWrapped(
                    graphics, Component.literal(sourceLine), x + 4, y, width - 4, MUTED, 10);
        }
        return y + 2;
    }

    private int renderWrapped(
            GuiGraphicsExtractor graphics,
            Component text,
            int x,
            int y,
            int width,
            int color,
            int lineHeight) {
        for (net.minecraft.util.FormattedCharSequence line : font.split(text, Math.max(20, width))) {
            graphics.text(font, line, x, y, color, false);
            y += lineHeight;
        }
        return y;
    }

    private Component toolFieldLabel(String name, String fallback) {
        String key = "screen.openallay.settings.tools.field." + name;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    private Component toolFieldDescription(String name, String fallback) {
        String key = "screen.openallay.settings.tools.field." + name + ".description";
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    private boolean hasToolFieldDescription(ToolField field) {
        return !field.description().isBlank()
                || Language.getInstance().has(
                        "screen.openallay.settings.tools.field." + field.name() + ".description");
    }

    private int toolCardsHeight(ToolSettingsProjection.Family family, int width) {
        return family.tools().stream().mapToInt(card -> toolCardHeight(card, width) + 6).sum();
    }

    private int toolCardHeight(ToolSettingsProjection.ToolCard card, int width) {
        int inner = Math.max(20, width - 14);
        int height = 7 + 13
                + wrappedHeight(Component.translatable(card.descriptionKey()), inner, 10)
                + 16;
        height += toolFieldsHeight(card.parameters().stream().map(parameter -> new ToolField(
                parameter.name(), parameter.label(), parameter.type(),
                parameter.required(), parameter.description())).toList(), inner, true);
        height += toolFieldsHeight(card.returns().stream().map(value -> new ToolField(
                value.name(), value.label(), value.type(), false, value.description())).toList(), inner, false);
        if (card.debug().isPresent()) {
            ToolSettingsProjection.Debug debug = card.debug().orElseThrow();
            height += 16;
            height += wrappedHeight(Component.literal(
                    "ID: " + debug.toolId() + " · alias: " + debug.modelAlias()), inner - 4, 10);
            height += wrappedHeight(Component.literal(
                    "provider: " + debug.providerId() + " · access: " + debug.access()), inner - 4, 10);
            height += wrappedHeight(Component.translatable(debug.executionKey()).copy()
                    .append(" · context: ")
                    .append(debug.requiredContext().isEmpty()
                            ? "none" : String.join(", ", debug.requiredContext())), inner - 4, 10);
            if (debug.diagnostic() != null) {
                height += wrappedHeight(
                        Component.literal("diagnostic: " + debug.diagnostic()), inner - 4, 10);
            }
            height += debugSchemaHeight(debug.inputSchema(), inner - 4);
            height += debugSchemaHeight(debug.outputSchema(), inner - 4);
        }
        return height + 7;
    }

    private int toolFieldsHeight(List<ToolField> fields, int width, boolean showRequired) {
        int height = 12;
        if (fields.isEmpty()) return height + 12;
        for (ToolField field : fields) {
            Component label = toolFieldLabel(field.name(), field.fallbackLabel()).copy()
                    .append(" · " + field.type());
            if (showRequired) {
                label = label.copy().append(" · ").append(Component.translatable(field.required()
                        ? "screen.openallay.settings.tools.required"
                        : "screen.openallay.settings.tools.optional"));
            }
            height += wrappedHeight(label, width - 5, 10);
            if (hasToolFieldDescription(field)) {
                height += wrappedHeight(
                        toolFieldDescription(field.name(), field.description()), width - 11, 10);
            }
        }
        return height + 2;
    }

    private int debugSchemaHeight(String schema, int width) {
        int height = 11;
        if (schema.isBlank()) return height + 11;
        for (String line : schema.split("\\R", -1)) {
            height += wrappedHeight(Component.literal(line), width - 4, 10);
        }
        return height + 2;
    }

    private int wrappedHeight(Component text, int width, int lineHeight) {
        return Math.max(1, font.split(text, Math.max(20, width)).size()) * lineHeight;
    }

    private record ToolField(
            String name,
            String fallbackLabel,
            String type,
            boolean required,
            String description) {}

    private void renderSkills(GuiGraphicsExtractor graphics) {
        SettingsLayout.Rect area = layout.editor();
        Optional<SkillSettingsProjection.Skill> selected = selectedSkill();
        graphics.text(
                font,
                Component.translatable("screen.openallay.settings.skills"),
                area.x() + 10,
                area.y() + 12,
                ACCENT,
                false);
        if (selected.isEmpty()) {
            graphics.text(
                    font,
                    Component.translatable("screen.openallay.settings.skills.empty"),
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
                        ? "screen.openallay.settings.skills.local"
                        : "screen.openallay.settings.skills.bundled"),
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
                        "screen.openallay.settings.reload",
                        () -> accept(service.reloadToolSettings(selectedTool, true))),
                new Action("screen.openallay.settings.done", this::onClose));
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
                    "screen.openallay.settings.confirm_reload").getString();
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
                    "screen.openallay.settings.history.confirm_reset_again");
        }
        return Component.translatable("screen.openallay.settings.confirm");
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
                                ? "screen.openallay.settings.history.confirm_reset"
                                : "screen.openallay.settings.history.confirm_delete")
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
                        "screen.openallay.settings.history.confirm_reset_again_notice")
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
                Component.translatable("screen.openallay.settings.tools.source.local_default")
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
        sourceDraft = null;
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
            return Component.translatable("screen.openallay.settings.recipe.preferred_auto");
        }
        return recipeProjection().sources().stream()
                .filter(source -> source.actionId().equals(recipeDraft.preferredViewer()))
                .findFirst()
                .<Component>map(source -> friendlyTitle(
                        source.titleKey(), "screen.openallay.settings.recipe.source.other"))
                .orElseGet(() -> Component.translatable(
                        "screen.openallay.settings.recipe.preferred_unavailable"));
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
                    "screen.openallay.settings.models.invalid").getString();
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
                    "screen.openallay.settings.models.cannot_delete_last").getString();
            return;
        }
        if (confirmation != Confirmation.DELETE) {
            confirmation = Confirmation.DELETE;
            localNotice = Component.translatable(
                    "screen.openallay.settings.confirm_delete").getString();
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
                    "screen.openallay.settings.models.save_first").getString();
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
                    "screen.openallay.settings.confirm_billable_test").getString();
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
        if (snapshot.operation().kind() == SettingsOperation.Kind.FETCHING_MODEL_CATALOG) {
            service.cancelModelCatalog();
        } else {
            service.cancelConnectionTest();
        }
    }

    private void refreshMetadata() {
        accept(service.refreshMetadata());
    }

    private void fetchModelCatalog() {
        captureDraft();
        ToolResult<ModelCatalogRequest> validated = draft.catalogRequest();
        if (validated instanceof ToolResult.Failure<ModelCatalogRequest> failure) {
            localNotice = Component.translatable(
                    "screen.openallay.settings.models.catalog_invalid").getString();
            return;
        }
        ModelCatalogRequest request =
                ((ToolResult.Success<ModelCatalogRequest>) validated).value();
        SecretValue replacement = pendingApiKey.isBlank()
                ? null
                : SecretValue.of(pendingApiKey);
        long generation = ++modelCatalogGeneration;
        service.fetchModelCatalog(request, replacement).thenAccept(result -> {
            if (generation != modelCatalogGeneration) {
                return;
            }
            if (result instanceof ToolResult.Success<ModelCatalog> success) {
                catalogModelIds = success.value().modelIds();
                modelCatalogPage = 0;
                modelCatalogOpen = true;
                localNotice = catalogModelIds.isEmpty()
                        ? Component.translatable(
                                "screen.openallay.settings.models.catalog_empty").getString()
                        : "";
                rebuildWidgets();
            } else {
                ToolResult.Failure<ModelCatalog> failure =
                        (ToolResult.Failure<ModelCatalog>) result;
                localNotice = Component.translatable(
                        "screen.openallay.settings.models.catalog_failed",
                        failure.message()).getString();
            }
        });
    }

    private void addModelCatalogPicker() {
        SettingsLayout.Rect area = layout.editor();
        int x = area.x() + 8;
        int y = area.y() + 30;
        int width = Math.max(80, area.width() - 16);
        int pageSize = modelCatalogPageSize();
        int start = Math.min(catalogModelIds.size(), modelCatalogPage * pageSize);
        int end = Math.min(catalogModelIds.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            String modelId = catalogModelIds.get(index);
            addRenderableWidget(OpenAllayButton.create(Component.literal(modelId), ignored -> {
                        draft = draft.withModel(modelId);
                        modelCatalogOpen = false;
                        localNotice = "";
                        rebuildWidgets();
                    })
                    .bounds(x, y, width, 20)
                    .build());
            y += 23;
        }
        int pages = Math.max(1, (catalogModelIds.size() + pageSize - 1) / pageSize);
        int navY = area.bottom() - 24;
        int navWidth = Math.max(30, (width - 8) / 3);
        Button previous = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.catalog_previous"),
                        ignored -> {
                            modelCatalogPage--;
                            rebuildWidgets();
                        })
                .bounds(x, navY, navWidth, 20)
                .build());
        previous.active = modelCatalogPage > 0;
        Button next = addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.catalog_next"),
                        ignored -> {
                            modelCatalogPage++;
                            rebuildWidgets();
                        })
                .bounds(x + navWidth + 4, navY, navWidth, 20)
                .build());
        next.active = modelCatalogPage + 1 < pages;
        addRenderableWidget(OpenAllayButton.create(
                        Component.translatable("screen.openallay.settings.models.catalog_close"),
                        ignored -> {
                            modelCatalogOpen = false;
                            rebuildWidgets();
                        })
                .bounds(x + (navWidth + 4) * 2, navY,
                        Math.max(30, width - (navWidth + 4) * 2), 20)
                .build());
    }

    private int modelCatalogPageSize() {
        return Math.max(1, (layout.editor().height() - 64) / 23);
    }

    private void invalidateModelCatalog() {
        modelCatalogGeneration++;
        catalogModelIds = List.of();
        modelCatalogOpen = false;
        modelCatalogPage = 0;
    }

    private void accept(java.util.concurrent.CompletableFuture<ToolResult<Boolean>> future) {
        future.thenAccept(result -> {
            if (result instanceof ToolResult.Failure<Boolean> failure) {
                localNotice = failure.code().equals("capability_dependency_conflict")
                        ? Component.translatable(
                                "screen.openallay.settings.capability.dependency_conflict")
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
        if (replacement != SettingsSection.MODELS) {
            modelCatalogOpen = false;
        }
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
        invalidateModelCatalog();
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
        invalidateModelCatalog();
        confirmation = Confirmation.NONE;
        localNotice = "";
        rebuildWidgets();
    }

    private boolean containsProfile(String profileId) {
        return snapshot.models().config().profiles().stream()
                .anyMatch(profile -> profile.id().equals(profileId));
    }

    private void captureDraft() {
        if (section == SettingsSection.MODELS && id != null) {
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
        if (section == SettingsSection.GENERAL && assistantName != null) {
            assistantNameDraft = assistantName.getValue();
        }
        captureSourceDraft();
    }

    private void captureSourceDraft() {
        if (section != SettingsSection.TOOLS
                || selectedToolSourceId == null
                || sourceDisplayName == null
                || sourceDirectory == null
                || sourceLocale == null) {
            return;
        }
        sourceDraft = new SourceDraft(
                selectedToolSourceId,
                sourceDisplayName.getValue(),
                sourceDirectory.getValue(),
                sourceLocale.getValue());
    }

    private void cycleProtocol() {
        captureDraft();
        draftProtocol = draftProtocol == ModelProtocol.OPENAI_CHAT
                ? ModelProtocol.ANTHROPIC_MESSAGES
                : ModelProtocol.OPENAI_CHAT;
        invalidateModelCatalog();
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
                        ? "screen.openallay.settings.models.protocol_openai"
                        : "screen.openallay.settings.models.protocol_anthropic");
    }

    private Component enabledLabel() {
        return Component.translatable(
                draftEnabled
                        ? "screen.openallay.settings.models.enabled"
                        : "screen.openallay.settings.models.disabled");
    }

    private String reloadKey() {
        return confirmation == Confirmation.RELOAD
                ? "screen.openallay.settings.confirm"
                : "screen.openallay.settings.reload";
    }

    private String deleteKey() {
        return confirmation == Confirmation.DELETE
                ? "screen.openallay.settings.confirm"
                : "screen.openallay.settings.delete";
    }

    private String testKey() {
        return confirmation == Confirmation.TEST_CONNECTION
                ? "screen.openallay.settings.confirm"
                : "screen.openallay.settings.models.test";
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

    /** Screenshot-harness navigation only; inert in every normal client launch. */
    public void e2eOpenTools() {
        e2eOpenTools(ToolFamilyId.GAME_CONTEXT);
    }

    /** Screenshot-harness navigation only; does not save or mutate Tool policy. */
    public void e2eOpenTools(ToolFamilyId family) {
        requireE2eControls();
        Objects.requireNonNull(family, "family");
        captureDraft();
        section = SettingsSection.TOOLS;
        selectedTool = family;
        selectedToolSourceId = null;
        sourceDraft = null;
        narrowToolDetail = true;
        pageScroll = 0;
        pageContentHeight = 0;
        rebuildWidgets();
    }

    /** Screenshot-harness navigation that exercises the real display-settings save path. */
    public void e2eOpenGeneral(String assistantDisplayName) {
        requireE2eControls();
        Objects.requireNonNull(assistantDisplayName, "assistantDisplayName");
        captureDraft();
        section = SettingsSection.GENERAL;
        assistantNameDraft = assistantDisplayName;
        pageScroll = 0;
        pageContentHeight = 0;
        accept(service.saveDisplay(snapshot.display().withAssistantName(assistantDisplayName)));
        rebuildWidgets();
    }

    /** Screenshot-harness navigation for the player-facing About page. */
    public void e2eOpenAbout() {
        requireE2eControls();
        captureDraft();
        section = SettingsSection.ABOUT;
        pageScroll = 0;
        pageContentHeight = 0;
        rebuildWidgets();
    }

    /** Positive pixels move the Tool detail down; intended for retained screenshot coverage. */
    public void e2eScrollToolDetails(int pixels) {
        requireE2eControls();
        if (section != SettingsSection.TOOLS || layout == null) {
            throw new IllegalStateException("E2E Tool details are not open");
        }
        captureSourceDraft();
        int maximum = Math.max(0, pageContentHeight - layout.content().height() + 18);
        pageScroll = net.minecraft.util.Mth.clamp(pageScroll + pixels, 0, maximum);
        rebuildWidgets();
    }

    static boolean e2eControlsEnabled() {
        return Boolean.getBoolean(GuideClientE2EConfig.ENABLED);
    }

    private static void requireE2eControls() {
        if (!e2eControlsEnabled()) {
            throw new IllegalStateException("OpenAllay E2E controls are disabled");
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

    private record SourceDraft(
            String sourceId, String displayName, String directory, String locale) {}

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
            return Component.translatable("screen.openallay.settings.models.api_key");
        }
    }
}
