package dev.tomewisp.settings.skill;

import dev.tomewisp.skill.BundledSkillLoader;
import dev.tomewisp.skill.FilesystemSkillLoader;
import dev.tomewisp.skill.SkillDocument;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.skill.SkillSettingsStore;
import dev.tomewisp.skill.SkillSource;
import dev.tomewisp.settings.ClientSettingsService;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Player-owned Skill document editing outside the Agent tool surface. */
public final class SkillSettingsBackend implements ClientSettingsService.SkillActions {
    private final SkillRepository repository;
    private final SkillParser parser;
    private final List<SkillSource> bundledSources;
    private final Path localRoot;
    private final Set<String> installedMods;
    private final FilesystemSkillLoader localLoader;
    private final SkillSettingsStore store;
    private volatile SkillSettingsView current = SkillSettingsView.empty();

    public SkillSettingsBackend(
            Path localRoot,
            SkillRepository repository,
            Set<String> installedMods) {
        this(
                localRoot,
                repository,
                new SkillParser(),
                new BundledSkillLoader().load(),
                installedMods,
                new FilesystemSkillLoader());
    }

    public SkillSettingsBackend(
            Path localRoot,
            SkillRepository repository,
            SkillParser parser,
            Collection<SkillSource> bundledSources,
            Set<String> installedMods) {
        this(localRoot, repository, parser, bundledSources, installedMods, new FilesystemSkillLoader());
    }

    SkillSettingsBackend(
            Path localRoot,
            SkillRepository repository,
            SkillParser parser,
            Collection<SkillSource> bundledSources,
            Set<String> installedMods,
            FilesystemSkillLoader localLoader) {
        this.localRoot = Objects.requireNonNull(localRoot, "localRoot")
                .toAbsolutePath()
                .normalize();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.bundledSources = List.copyOf(bundledSources);
        this.installedMods = Set.copyOf(installedMods);
        this.localLoader = Objects.requireNonNull(localLoader, "localLoader");
        this.store = new SkillSettingsStore(this.localRoot, parser);
        reloadInternal();
    }

    public SkillSettingsView currentView() {
        return current;
    }

    @Override
    public synchronized ToolResult<SkillSettingsView> reloadSkills() {
        try {
            reloadInternal();
            return new ToolResult.Success<>(current);
        } catch (RuntimeException failure) {
            return failure("skill_reload_failed", "Unable to reload Skills", failure);
        }
    }

    /** Saves a local override; a bundled selection is copied before its entry is replaced. */
    @Override
    public synchronized ToolResult<SkillSettingsView> saveOverride(String name, String markdown) {
        SkillSettingsView.Skill selected = current.find(name).orElse(null);
        if (selected == null) {
            return new ToolResult.Failure<>("skill_not_found", "The selected Skill is unavailable");
        }
        if (markdown == null || markdown.isBlank()) {
            return new ToolResult.Failure<>("skill_override_invalid", "Skill Markdown must not be blank");
        }

        FilesystemSkillLoader.LoadResult local = localLoader.load(localRoot);
        SkillSource previousLocal = sourceNamed(local.sources(), name);
        boolean hadOverride = store.hasOverride(name);
        if (hadOverride && previousLocal == null) {
            return new ToolResult.Failure<>(
                    "skill_override_invalid",
                    "The existing local override is invalid; delete it before creating a replacement");
        }
        SkillSource base = previousLocal != null ? previousLocal : bundledNamed(name);
        if (base == null) {
            return new ToolResult.Failure<>(
                    "skill_override_unavailable", "The selected Skill cannot be overridden");
        }

        SkillDocument candidate;
        try {
            candidate = parser.parse(candidateSource(base, markdown));
        } catch (RuntimeException failure) {
            return failure("skill_override_invalid", "The Skill override is invalid", failure);
        }

        String previousMarkdown = previousLocal == null ? null : entryMarkdown(previousLocal);
        boolean created = false;
        try {
            if (!hadOverride) {
                SkillSource bundled = bundledNamed(name);
                if (bundled == null) {
                    return new ToolResult.Failure<>(
                            "skill_override_unavailable", "The selected Skill cannot be overridden");
                }
                store.createOverride(bundled);
                created = true;
            }
            store.editOverride(name, markdown);
            reloadInternal();
            SkillDocument published = repository.find(name).orElse(null);
            if (!sameContent(candidate, published)
                    || published.metadata().origin() != SkillSource.Origin.LOCAL) {
                rollback(name, created, previousMarkdown);
                reloadInternal();
                return new ToolResult.Failure<>(
                        "skill_override_dependency_unavailable",
                        "The Skill override requires unavailable Tools or mods");
            }
            return new ToolResult.Success<>(current);
        } catch (RuntimeException failure) {
            try {
                rollback(name, created, previousMarkdown);
                reloadInternal();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            return failure("skill_override_save_failed", "Unable to save the Skill override", failure);
        }
    }

    @Override
    public synchronized ToolResult<SkillSettingsView> deleteOverride(String name) {
        try {
            if (!store.hasOverride(name)) {
                return new ToolResult.Failure<>(
                        "skill_override_not_found", "The selected Skill has no local override");
            }
            store.deleteOverride(name);
            reloadInternal();
            return new ToolResult.Success<>(current);
        } catch (RuntimeException failure) {
            return failure("skill_override_delete_failed", "Unable to delete the Skill override", failure);
        }
    }

    private void reloadInternal() {
        FilesystemSkillLoader.LoadResult local = localLoader.load(localRoot);
        repository.reload(bundledSources, local, installedMods);
        current = buildView(local);
    }

    private SkillSettingsView buildView(FilesystemSkillLoader.LoadResult local) {
        Map<String, ParsedSource> parsedSources = new LinkedHashMap<>();
        for (SkillSource source : bundledSources) {
            addParsed(parsedSources, source);
        }
        for (SkillSource source : local.sources()) {
            addParsed(parsedSources, source);
        }

        List<SkillSettingsView.Skill> skills = new ArrayList<>();
        for (var metadata : repository.metadata()) {
            SkillDocument document = repository.find(metadata.name()).orElseThrow();
            ParsedSource parsed = parsedSources.get(metadata.name());
            String markdown = null;
            if (parsed != null
                    && parsed.document().metadata().origin() == metadata.origin()
                    && sameContent(parsed.document(), document)) {
                markdown = entryMarkdown(parsed.source());
            }
            if (markdown == null) {
                SkillSettingsView.Skill previous = current.find(metadata.name()).orElse(null);
                if (previous != null
                        && previous.origin() == metadata.origin()
                        && previous.body().equals(document.instructions())) {
                    markdown = previous.markdown();
                }
            }
            if (markdown == null) {
                throw new IllegalStateException("Validated Skill source is unavailable: " + metadata.name());
            }
            skills.add(new SkillSettingsView.Skill(
                    metadata,
                    document.instructions(),
                    markdown,
                    store.hasOverride(metadata.name())));
        }
        return new SkillSettingsView(skills, repository.diagnostics());
    }

    private void addParsed(Map<String, ParsedSource> parsedSources, SkillSource source) {
        try {
            SkillDocument document = parser.parse(source);
            parsedSources.put(document.metadata().name(), new ParsedSource(source, document));
        } catch (RuntimeException ignored) {
            // Repository diagnostics own invalid-package reporting and last-valid retention.
        }
    }

    private SkillSource bundledNamed(String name) {
        return sourceNamed(bundledSources, name);
    }

    private SkillSource sourceNamed(Collection<SkillSource> sources, String name) {
        for (SkillSource source : sources) {
            try {
                if (parser.parse(source).metadata().name().equals(name)) {
                    return source;
                }
            } catch (RuntimeException ignored) {
                // Invalid local packages are isolated by the loader/repository contract.
            }
        }
        return null;
    }

    private SkillSource candidateSource(SkillSource base, String markdown) {
        Map<String, String> files = new LinkedHashMap<>(base.files());
        files.put(base.entryPath(), markdown);
        return new SkillSource(
                "local-candidate:" + base.directoryName(),
                base.entryPath(),
                files,
                SkillSource.Origin.LOCAL);
    }

    private void rollback(String name, boolean created, String previousMarkdown) {
        if (created) {
            if (store.hasOverride(name)) {
                store.deleteOverride(name);
            }
            return;
        }
        if (previousMarkdown != null) {
            store.editOverride(name, previousMarkdown);
        }
    }

    private static boolean sameContent(SkillDocument expected, SkillDocument actual) {
        if (actual == null) {
            return false;
        }
        var left = expected.metadata();
        var right = actual.metadata();
        return left.name().equals(right.name())
                && left.description().equals(right.description())
                && left.license().equals(right.license())
                && left.compatibility().equals(right.compatibility())
                && left.attributes().equals(right.attributes())
                && left.requiredMods().equals(right.requiredMods())
                && left.allowedTools().equals(right.allowedTools())
                && left.references().equals(right.references())
                && expected.instructions().equals(actual.instructions())
                && expected.references().equals(actual.references());
    }

    private static String entryMarkdown(SkillSource source) {
        String markdown = source.files().get(source.entryPath());
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("Skill entry Markdown is unavailable");
        }
        return markdown;
    }

    private static <T> ToolResult.Failure<T> failure(
            String code, String message, RuntimeException failure) {
        String detail = failure.getMessage();
        return new ToolResult.Failure<>(
                code,
                detail == null || detail.isBlank() ? message : message + ": " + detail);
    }

    private record ParsedSource(SkillSource source, SkillDocument document) {}
}
