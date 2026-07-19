package dev.openallay.tool.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeDocument;
import dev.openallay.knowledge.KnowledgeKind;
import dev.openallay.knowledge.KnowledgeLoad;
import dev.openallay.knowledge.KnowledgeSourceProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads direct Markdown documents from one source-owned directory below a managed root. */
public final class LocalMarkdownKnowledgeProvider implements KnowledgeSourceProvider {
    private static final Set<String> CONFIG_FIELDS = Set.of("directory", "locale");
    private static final Pattern DIRECTORY = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,8}(?:_[a-z0-9]{2,8})*");

    private final ToolSourceDefinition definition;
    private final Path managedRoot;
    private final String directory;
    private final String locale;
    private final String gameVersion;
    private final String loader;
    private final Clock clock;

    public LocalMarkdownKnowledgeProvider(
            ToolSourceDefinition definition,
            Path managedRoot,
            String gameVersion,
            String loader) {
        this(definition, managedRoot, gameVersion, loader, Clock.systemUTC());
    }

    public LocalMarkdownKnowledgeProvider(
            ToolSourceDefinition definition,
            Path managedRoot,
            String gameVersion,
            String loader,
            Clock clock) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (!definition.sourceKind().equals("local_markdown")) {
            throw new IllegalArgumentException("Local Markdown provider requires local_markdown source kind");
        }
        if (definition.lifecycle() != ToolSourceDefinition.Lifecycle.USER) {
            throw new IllegalArgumentException("Local Markdown sources must use USER lifecycle");
        }
        JsonObject config = validateConfig(definition.config());
        this.directory = config.get("directory").getAsString();
        this.locale = config.get("locale").getAsString();
        this.managedRoot = Objects.requireNonNull(managedRoot, "managedRoot")
                .toAbsolutePath()
                .normalize();
        this.gameVersion = require(gameVersion, "gameVersion");
        this.loader = require(loader, "loader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Strict kind-specific codec used by the trusted source-kind registry. */
    public static JsonObject validateConfig(JsonObject candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.keySet().equals(CONFIG_FIELDS)) {
            throw new ToolConfigException(
                    "invalid_source_config",
                    "local_markdown config fields must be exactly " + CONFIG_FIELDS);
        }
        String directory = string(candidate, "directory");
        if (!DIRECTORY.matcher(directory).matches()
                || directory.equals(".")
                || directory.equals("..")) {
            throw new ToolConfigException(
                    "managed_root_escape",
                    "Local Markdown directory must be one safe source-owned name");
        }
        String locale = string(candidate, "locale");
        if (!LOCALE.matcher(locale).matches()) {
            throw new ToolConfigException(
                    "invalid_source_config", "Local Markdown locale is invalid");
        }
        JsonObject validated = new JsonObject();
        validated.addProperty("directory", directory);
        validated.addProperty("locale", locale);
        return validated;
    }

    @Override
    public String sourceId() {
        return definition.sourceId();
    }

    public Path sourceDirectory() {
        return managedRoot.resolve(directory).normalize();
    }

    @Override
    public KnowledgeLoad load() throws IOException {
        Path root = verifiedManagedRoot();
        Path source = sourceDirectory();
        if (!source.startsWith(managedRoot) || Files.isSymbolicLink(source)) {
            throw new IllegalArgumentException("Local Markdown source escapes its managed root");
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Local Markdown source directory is unavailable");
        }
        Path realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realSource.startsWith(root)) {
            throw new IllegalArgumentException("Local Markdown source escapes its managed root");
        }

        List<Path> markdown;
        try (var entries = Files.list(realSource)) {
            markdown = entries
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        Instant capturedAt = clock.instant();
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (Path file : markdown) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Local Markdown documents cannot be symbolic links");
            }
            Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(realSource)) {
                throw new IllegalArgumentException("Local Markdown document escapes its source directory");
            }
            String fileName = file.getFileName().toString();
            String documentId = fileName.substring(0, fileName.length() - 3);
            String body = Files.readString(realFile);
            EvidenceMetadata evidence = evidence(
                    capturedAt,
                    DataCompleteness.COMPLETE,
                    Map.of(
                            "openallay:document_id", documentId,
                            "openallay:locale", locale));
            documents.add(new KnowledgeDocument(
                    definition.sourceId(),
                    documentId,
                    KnowledgeKind.GUIDE_ENTRY,
                    title(body, documentId),
                    body,
                    locale,
                    Set.of(),
                    Set.of(),
                    null,
                    true,
                    "local_markdown",
                    evidence));
        }
        List<EvidenceMetadata> evidence = documents.isEmpty()
                ? List.of(evidence(
                        capturedAt,
                        DataCompleteness.COMPLETE,
                        Map.of(
                                "openallay:document_count", "0",
                                "openallay:locale", locale)))
                : documents.stream().map(KnowledgeDocument::evidence).toList();
        return new KnowledgeLoad(documents, List.of(), evidence);
    }

    private Path verifiedManagedRoot() throws IOException {
        if (Files.isSymbolicLink(managedRoot)
                || !Files.isDirectory(managedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Local Markdown managed root is unavailable or symbolic");
        }
        return managedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private EvidenceMetadata evidence(
            Instant capturedAt, DataCompleteness completeness, Map<String, String> details) {
        return new EvidenceMetadata(
                DataAuthority.RESOURCE_ASSET,
                completeness,
                capturedAt,
                definition.sourceId(),
                "openallay:local_markdown",
                gameVersion,
                loader,
                details);
    }

    private static String title(String body, String fallback) {
        for (String line : body.lines().toList()) {
            if (line.startsWith("# ") && !line.substring(2).isBlank()) {
                return line.substring(2).strip();
            }
        }
        return fallback;
    }

    private static String string(JsonObject object, String field) {
        JsonElement encoded = object.get(field);
        if (encoded == null
                || !encoded.isJsonPrimitive()
                || !encoded.getAsJsonPrimitive().isString()
                || encoded.getAsString().isBlank()) {
            throw new ToolConfigException(
                    "invalid_source_config", field + " must be a non-blank string");
        }
        return encoded.getAsString();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
